/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.nodes;

import jdk.vm.ci.meta.MetaUtil;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Equivalence;
import org.graalvm.collections.MapCursor;
import org.graalvm.collections.UnmodifiableEconomicMap;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * This class contains all inlining decisions performed on a graph during the compilation.
 *
 * Each inlining decision consists of:
 *
 * <ul>
 * <li>a value indicating whether the decision was positive or negative</li>
 * <li>the call target method</li>
 * <li>the reason for the inlining decision</li>
 * <li>the name of the phase in which the inlining decision took place</li>
 * <li>the inlining log of the inlined graph, or {@code null} if the decision was negative</li>
 * </ul>
 *
 * A phase that does inlining should use the instance of this class contained in the
 * {@link StructuredGraph} by calling {@link #addDecision} whenever it decides to inline a method.
 * If there are invokes in the graph at the end of the respective phase, then that phase must call
 * {@link #addDecision} to log negative decisions.
 */
public class InliningLog {
    public static final class Decision {
        private final boolean positive;
        private final String reason;
        private final String phase;
        private final ResolvedJavaMethod target;

        private Decision(boolean positive, String reason, String phase, ResolvedJavaMethod target) {
            this.positive = positive;
            this.reason = reason;
            this.phase = phase;
            this.target = target;
        }

        public boolean isPositive() {
            return positive;
        }

        public String getReason() {
            return reason;
        }

        public String getPhase() {
            return phase;
        }

        public ResolvedJavaMethod getTarget() {
            return target;
        }

        @Override
        public String toString() {
            return String.format("<%s> %s: %s", phase, target != null ? target.format("%H.%n(%p)") : "", reason);
        }
    }

    private class Callsite {
        public final List<Decision> decisions;
        public final List<Callsite> children;
        public Callsite parent;
        public ResolvedJavaMethod target;
        public Invokable invoke;

        Callsite(Callsite parent, Invokable originalInvoke) {
            this.parent = parent;
            this.decisions = new ArrayList<>();
            this.children = new ArrayList<>();
            this.invoke = originalInvoke;
        }

        public Callsite addChild(Invokable childInvoke) {
            Callsite child = new Callsite(this, childInvoke);
            children.add(child);
            return child;
        }

        public String positionString() {
            if (parent == null) {
                return "<root>";
            }
            return MetaUtil.appendLocation(new StringBuilder(100), parent.target, getBci()).toString();
        }

        public int getBci() {
            return invoke != null ? invoke.bci() : -1;
        }
    }

    private final Callsite root;
    private final EconomicMap<Invokable, Callsite> leaves;

    public InliningLog(ResolvedJavaMethod rootMethod) {
        this.root = new Callsite(null, null);
        this.root.target = rootMethod;
        this.leaves = EconomicMap.create(Equivalence.IDENTITY_WITH_SYSTEM_HASHCODE);
    }

    /**
     * Add an inlining decision for the specified invoke.
     *
     * An inlining decision can be either positive or negative. A positive inlining decision must be
     * logged after replacing an {@link Invoke} with a graph. In this case, the node replacement map
     * and the {@link InliningLog} of the inlined graph must be provided.
     */
    public void addDecision(Invokable invoke, boolean positive, String reason, String phase, EconomicMap<Node, Node> replacements, InliningLog calleeLog) {
        assert leaves.containsKey(invoke);
        assert (!positive && replacements == null && calleeLog == null) || (positive && replacements != null && calleeLog != null);
        Callsite callsite = leaves.get(invoke);
        callsite.target = callsite.invoke.getTargetMethod();
        Decision decision = new Decision(positive, reason, phase, invoke.getTargetMethod());
        callsite.decisions.add(decision);
        if (positive) {
            leaves.removeKey(invoke);
            EconomicMap<Callsite, Callsite> mapping = EconomicMap.create(Equivalence.IDENTITY_WITH_SYSTEM_HASHCODE);
            for (Callsite calleeChild : calleeLog.root.children) {
                Callsite child = callsite.addChild(calleeChild.invoke);
                copyTree(child, calleeChild, replacements, mapping);
            }
            MapCursor<Invokable, Callsite> entries = calleeLog.leaves.getEntries();
            while (entries.advance()) {
                Invokable invokeFromCallee = entries.getKey();
                Callsite callsiteFromCallee = entries.getValue();
                if (invokeFromCallee.asFixedNode().isDeleted()) {
                    // Some invoke nodes could have been removed by optimizations.
                    continue;
                }
                Invokable inlinedInvokeFromCallee = (Invokable) replacements.get(invokeFromCallee.asFixedNode());
                Callsite descendant = mapping.get(callsiteFromCallee);
                leaves.put(inlinedInvokeFromCallee, descendant);
            }
        }
    }

    /**
     * Append the inlining decision tree from the specified log.
     *
     * The subtrees of the specified log are appended below the root of this log. This is usually
     * called when a node in the graph is replaced with its snippet.
     *
     * @see InliningLog#addDecision
     */
    public void addLog(UnmodifiableEconomicMap<Node, Node> replacements, InliningLog replacementLog) {
        EconomicMap<Callsite, Callsite> mapping = EconomicMap.create(Equivalence.IDENTITY_WITH_SYSTEM_HASHCODE);
        for (Callsite calleeChild : replacementLog.root.children) {
            Callsite child = root.addChild(calleeChild.invoke);
            copyTree(child, calleeChild, replacements, mapping);
        }
        MapCursor<Invokable, Callsite> entries = replacementLog.leaves.getEntries();
        while (entries.advance()) {
            Invokable replacementInvoke = entries.getKey();
            Callsite replacementCallsite = entries.getValue();
            if (replacementInvoke.asFixedNode().isDeleted()) {
                // Some invoke nodes could have been removed by optimizations.
                continue;
            }
            Invokable invoke = (Invokable) replacements.get(replacementInvoke.asFixedNode());
            Callsite callsite = mapping.get(replacementCallsite);
            leaves.put(invoke, callsite);
        }
    }

    /**
     * Completely replace the current log with the copy of the specified log.
     *
     * The precondition is that the current inlining log is completely empty. This is usually called
     * when copying the entire graph.
     *
     * @see InliningLog#addDecision
     */
    public void replaceLog(UnmodifiableEconomicMap<Node, Node> replacements, InliningLog replacementLog) {
        assert root.decisions.isEmpty();
        assert root.children.isEmpty();
        assert leaves.isEmpty();
        EconomicMap<Callsite, Callsite> mapping = EconomicMap.create(Equivalence.IDENTITY_WITH_SYSTEM_HASHCODE);
        copyTree(root, replacementLog.root, replacements, mapping);
        MapCursor<Invokable, Callsite> replacementEntries = replacementLog.leaves.getEntries();
        while (replacementEntries.advance()) {
            Invokable replacementInvoke = replacementEntries.getKey();
            Callsite replacementSite = replacementEntries.getValue();
            Invokable invoke = (Invokable) replacements.get((Node) replacementInvoke);
            Callsite site = mapping.get(replacementSite);
            leaves.put(invoke, site);
        }
    }

    private void copyTree(Callsite site, Callsite replacementSite, UnmodifiableEconomicMap<Node, Node> replacements, EconomicMap<Callsite, Callsite> mapping) {
        mapping.put(replacementSite, site);
        site.target = replacementSite.target;
        site.decisions.addAll(replacementSite.decisions);
        site.invoke = replacementSite.invoke != null && replacementSite.invoke.asFixedNode().isAlive() ? (Invokable) replacements.get(replacementSite.invoke.asFixedNode()) : null;
        for (Callsite replacementChild : replacementSite.children) {
            Callsite child = new Callsite(site, null);
            site.children.add(child);
            copyTree(child, replacementChild, replacements, mapping);
        }
    }

    public void checkInvariants(StructuredGraph graph) {
        for (Invoke invoke : graph.getInvokes()) {
            assert leaves.containsKey(invoke) : "Invoke " + invoke + " not contained in the leaves.";
        }
        assert root.parent == null;
        checkTreeInvariants(root);
    }

    private void checkTreeInvariants(Callsite site) {
        for (Callsite child : site.children) {
            assert site == child.parent : "Callsite " + site + " with child " + child + " has an invalid parent pointer " + site;
            checkTreeInvariants(child);
        }
    }

    private UpdateScope activated = null;

    /**
     * Used to designate scopes in which {@link Invokable} registration or cloning should be handled
     * differently.
     */
    public class UpdateScope implements AutoCloseable {
        private BiConsumer<Invokable, Invokable> updater;

        private UpdateScope(BiConsumer<Invokable, Invokable> updater) {
            this.updater = updater;
            if (activated != null) {
                throw GraalError.shouldNotReachHere("InliningLog updating already set.");
            }
            activated = this;
        }

        @Override
        public void close() {
            assert activated != null;
            activated = null;
        }

        public BiConsumer<Invokable, Invokable> getUpdater() {
            return updater;
        }
    }

    public BiConsumer<Invokable, Invokable> getUpdateScope() {
        if (activated == null) {
            return null;
        }
        return activated.getUpdater();
    }

    /**
     * Creates and sets a new update scope for the log.
     *
     * The specified lambda is invoked when an {@link Invokable} node is registered or cloned. If
     * the node is newly registered, then the first argument to the lambda is {@code null} If the
     * node is cloned, then the first argument is the node it was cloned from.
     *
     * @param updater a lambda taking a null (or the original node), and the registered (or cloned)
     *            {@link Invokable}
     */
    public UpdateScope createUpdateScope(BiConsumer<Invokable, Invokable> updater) {
        return new UpdateScope(updater);
    }

    public boolean containsLeafCallsite(Invokable invokable) {
        return leaves.containsKey(invokable);
    }

    public void removeLeafCallsite(Invokable invokable) {
        leaves.removeKey(invokable);
    }

    public void trackNewCallsite(Invokable invoke) {
        assert !leaves.containsKey(invoke);
        Callsite callsite = new Callsite(root, invoke);
        root.children.add(callsite);
        leaves.put(invoke, callsite);
    }

    public void trackDuplicatedCallsite(Invokable sibling, Invokable newInvoke) {
        Callsite siblingCallsite = leaves.get(sibling);
        Callsite parentCallsite = siblingCallsite.parent;
        Callsite callsite = parentCallsite.addChild(newInvoke);
        leaves.put(newInvoke, callsite);
    }

    public void updateExistingCallsite(Invokable previousInvoke, Invokable newInvoke) {
        Callsite callsite = leaves.get(previousInvoke);
        leaves.removeKey(previousInvoke);
        leaves.put(newInvoke, callsite);
        callsite.invoke = newInvoke;
    }

    public String formatAsTree() {
        StringBuilder builder = new StringBuilder(512);
        formatAsTree(root, "", builder);
        return builder.toString();
    }

    private void formatAsTree(Callsite site, String indent, StringBuilder builder) {
        String position = site.positionString();
        builder.append(indent).append("at ").append(position).append(": ");
        if (site.decisions.isEmpty()) {
            builder.append(System.lineSeparator());
        } else {
            for (Decision decision : site.decisions) {
                builder.append(decision.toString());
                builder.append(System.lineSeparator());
            }
        }
        for (Callsite child : site.children) {
            formatAsTree(child, indent + "  ", builder);
        }
    }
}
