// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.rules.analysis;

import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.FunctionRegistry;
import org.apache.doris.common.Pair;
import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.SqlCacheContext;
import org.apache.doris.nereids.StatementContext;
import org.apache.doris.nereids.analyzer.MappingSlot;
import org.apache.doris.nereids.analyzer.Scope;
import org.apache.doris.nereids.analyzer.UnboundFunction;
import org.apache.doris.nereids.analyzer.UnboundOneRowRelation;
import org.apache.doris.nereids.analyzer.UnboundResultSink;
import org.apache.doris.nereids.analyzer.UnboundSlot;
import org.apache.doris.nereids.analyzer.UnboundStar;
import org.apache.doris.nereids.analyzer.UnboundTVFRelation;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.parser.LogicalPlanBuilder;
import org.apache.doris.nereids.pattern.MatchingContext;
import org.apache.doris.nereids.properties.OrderKey;
import org.apache.doris.nereids.rules.AppliedAwareRule.AppliedAwareRuleCondition;
import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.rules.expression.ExpressionRewriteContext;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.BoundStar;
import org.apache.doris.nereids.trees.expressions.DefaultValueSlot;
import org.apache.doris.nereids.trees.expressions.EqualTo;
import org.apache.doris.nereids.trees.expressions.ExprId;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Properties;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.StatementScopeIdGenerator;
import org.apache.doris.nereids.trees.expressions.functions.BoundFunction;
import org.apache.doris.nereids.trees.expressions.functions.Function;
import org.apache.doris.nereids.trees.expressions.functions.FunctionBuilder;
import org.apache.doris.nereids.trees.expressions.functions.agg.AggregateFunction;
import org.apache.doris.nereids.trees.expressions.functions.agg.AnyValue;
import org.apache.doris.nereids.trees.expressions.functions.agg.NullableAggregateFunction;
import org.apache.doris.nereids.trees.expressions.functions.generator.TableGeneratingFunction;
import org.apache.doris.nereids.trees.expressions.functions.scalar.GroupingScalarFunction;
import org.apache.doris.nereids.trees.expressions.functions.scalar.StructElement;
import org.apache.doris.nereids.trees.expressions.functions.table.TableValuedFunction;
import org.apache.doris.nereids.trees.expressions.literal.IntegerLikeLiteral;
import org.apache.doris.nereids.trees.expressions.literal.StringLiteral;
import org.apache.doris.nereids.trees.expressions.visitor.ExpressionVisitors;
import org.apache.doris.nereids.trees.plans.JoinType;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.algebra.Aggregate;
import org.apache.doris.nereids.trees.plans.algebra.InlineTable;
import org.apache.doris.nereids.trees.plans.algebra.OneRowRelation;
import org.apache.doris.nereids.trees.plans.algebra.SetOperation;
import org.apache.doris.nereids.trees.plans.algebra.SetOperation.Qualifier;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalCatalogRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalExcept;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalGenerate;
import org.apache.doris.nereids.trees.plans.logical.LogicalHaving;
import org.apache.doris.nereids.trees.plans.logical.LogicalIntersect;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.logical.LogicalLoadProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalOneRowRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.nereids.trees.plans.logical.LogicalPreFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalQualify;
import org.apache.doris.nereids.trees.plans.logical.LogicalRepeat;
import org.apache.doris.nereids.trees.plans.logical.LogicalResultSink;
import org.apache.doris.nereids.trees.plans.logical.LogicalSetOperation;
import org.apache.doris.nereids.trees.plans.logical.LogicalSink;
import org.apache.doris.nereids.trees.plans.logical.LogicalSort;
import org.apache.doris.nereids.trees.plans.logical.LogicalSubQueryAlias;
import org.apache.doris.nereids.trees.plans.logical.LogicalTVFRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalUsingJoin;
import org.apache.doris.nereids.trees.plans.logical.ProjectProcessor;
import org.apache.doris.nereids.trees.plans.visitor.InferPlanOutputAlias;
import org.apache.doris.nereids.types.BooleanType;
import org.apache.doris.nereids.types.StructField;
import org.apache.doris.nereids.types.StructType;
import org.apache.doris.nereids.util.ExpressionUtils;
import org.apache.doris.nereids.util.PlanUtils;
import org.apache.doris.nereids.util.PlanUtils.CollectNonWindowedAggFuncs;
import org.apache.doris.nereids.util.TypeCoercionUtils;
import org.apache.doris.nereids.util.Utils;
import org.apache.doris.qe.SqlModeHelper;

import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * BindSlotReference.
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class BindExpression implements AnalysisRuleFactory {
    public static final Logger LOG = LogManager.getLogger(BindExpression.class);

    @Override
    public List<Rule> buildRules() {
        /*
         * some rules not only depends on the condition Plan::canBind, for example,
         * BINDING_FILTER_SLOT need transform 'filter(unix_timestamp() > 100)' to
         * 'filter(unix_timestamp() > cast(100 as int))'. there is no any unbound expression
         * in the filter, so the Plan::canBind return false.
         *
         * we need `isAppliedRule` to judge whether a plan is applied to a rule, so need convert
         * the normal rule to `AppliedAwareRule` to read and write the mutable state.
         */
        AppliedAwareRuleCondition ruleCondition = new AppliedAwareRuleCondition() {
            @Override
            protected boolean condition(Rule rule, Plan plan) {
                if (!rule.getPattern().matchRoot(plan)) {
                    return false;
                }
                return !isAppliedRule(rule, plan);
            }
        };

        return ImmutableList.of(
            RuleType.BINDING_PROJECT_SLOT.build(
                logicalProject().thenApply(this::bindProject)
            ),
            RuleType.BINDING_LOAD_PROJECT_SLOT.build(
                logicalLoadProject().thenApply(this::bindLoadProject)
            ),
            RuleType.BINDING_FILTER_SLOT.build(
                logicalFilter().thenApply(this::bindFilter)
            ),
            RuleType.BINDING_PRE_FILTER_SLOT.build(
                logicalPreFilter().thenApply(this::bindPreFilter)
            ),
            RuleType.BINDING_USING_JOIN_SLOT.build(
                logicalUsingJoin().thenApply(this::bindUsingJoin)
            ),
            RuleType.BINDING_JOIN_SLOT.build(
                logicalJoin().thenApply(this::bindJoin)
            ),
            RuleType.BINDING_AGGREGATE_SLOT.build(
                logicalAggregate().thenApply(this::bindAggregate)
            ),
            RuleType.BINDING_REPEAT_SLOT.build(
                logicalRepeat().thenApply(this::bindRepeat)
            ),
            RuleType.BINDING_SORT_SLOT.build(
                logicalSort(any().whenNot(SetOperation.class::isInstance))
                        .thenApply(this::bindSortWithoutSetOperation)
            ),
            RuleType.BINDING_SORT_SET_OPERATION_SLOT.build(
                logicalSort(logicalSetOperation()).thenApply(this::bindSortWithSetOperation)
            ),
            RuleType.BINDING_HAVING_SLOT.build(
                logicalHaving(aggregate()).thenApply(this::bindHavingAggregate)
            ),
            RuleType.BINDING_HAVING_SLOT.build(
                logicalHaving(any().whenNot(Aggregate.class::isInstance)).thenApply(this::bindHaving)
            ),
            RuleType.BINDING_QUALIFY_PROJECT_SLOT.build(
                logicalQualify(logicalProject()).thenApply(this::bindQualifyProject)
            ),
            RuleType.BINDING_QUALIFY_AGGREGATE_SLOT.build(
                logicalQualify(aggregate()).thenApply(this::bindQualifyAggregate)
            ),
            RuleType.BINDING_QUALIFY_HAVING_SLOT.build(
                logicalQualify(logicalHaving()).thenApply(this::bindQualifyHaving)
            ),
            RuleType.BINDING_INLINE_TABLE_SLOT.build(
                inlineTable().thenApply(this::bindInlineTable)
            ),
            RuleType.BINDING_ONE_ROW_RELATION_SLOT.build(
                // we should bind UnboundAlias in the UnboundOneRowRelation
                oneRowRelation().thenApply(this::bindOneRowRelation)
            ),
            RuleType.BINDING_SET_OPERATION_SLOT.build(
                // LogicalSetOperation don't bind again if LogicalSetOperation.outputs is not empty, this is special
                // we should not remove LogicalSetOperation::canBind, because in default case, the plan can run into
                // bind callback if not bound or **not run into bind callback yet**.
                logicalSetOperation().then(this::bindSetOperation)
            ),
            RuleType.BINDING_GENERATE_SLOT.build(
                logicalGenerate().thenApply(this::bindGenerate)
            ),
            RuleType.BINDING_UNBOUND_TVF_RELATION_FUNCTION.build(
                unboundTVFRelation().thenApply(this::bindTableValuedFunction)
            ),
            RuleType.BINDING_SUBQUERY_ALIAS_SLOT.build(
                logicalSubQueryAlias().thenApply(this::bindSubqueryAlias)
            ),
            RuleType.BINDING_RESULT_SINK.build(
                unboundResultSink().thenApply(this::bindResultSink)
            )
        ).stream().map(ruleCondition).collect(ImmutableList.toImmutableList());
    }

    private LogicalResultSink<Plan> bindResultSink(MatchingContext<UnboundResultSink<Plan>> ctx) {
        LogicalSink<Plan> sink = ctx.root;
        if (ctx.connectContext.getState().isQuery()) {
            List<NamedExpression> outputExprs = sink.child().getOutput().stream()
                    .map(NamedExpression.class::cast)
                    .collect(ImmutableList.toImmutableList());
            return new LogicalResultSink<>(outputExprs, sink.child());
        }
        // Should infer column name for expression when query command
        final ImmutableListMultimap.Builder<ExprId, Integer> exprIdToIndexMapBuilder =
                ImmutableListMultimap.builder();
        List<Slot> childOutput = sink.child().getOutput();
        for (int index = 0; index < childOutput.size(); index++) {
            exprIdToIndexMapBuilder.put(childOutput.get(index).getExprId(), index);
        }
        InferPlanOutputAlias aliasInfer = new InferPlanOutputAlias(childOutput);
        List<NamedExpression> output = aliasInfer.infer(sink.child(), exprIdToIndexMapBuilder.build());
        return new LogicalResultSink<>(output, sink.child());
    }

    private LogicalSubQueryAlias<Plan> bindSubqueryAlias(MatchingContext<LogicalSubQueryAlias<Plan>> ctx) {
        return ctx.root;
    }

    private LogicalPlan bindGenerate(MatchingContext<LogicalGenerate<Plan>> ctx) {
        LogicalGenerate<Plan> generate = ctx.root;

        // already bounded, then fast return
        for (Slot slot : generate.getGeneratorOutput()) {
            if (!(slot instanceof UnboundSlot)) {
                return generate;
            }
        }

        CascadesContext cascadesContext = ctx.cascadesContext;

        Builder<Slot> outputSlots = ImmutableList.builder();
        Builder<Function> boundGenerators = ImmutableList.builder();
        List<Alias> expandAlias = Lists.newArrayList();
        SimpleExprAnalyzer analyzer = buildSimpleExprAnalyzer(generate, cascadesContext, generate.children());
        for (int i = 0; i < generate.getGeneratorOutput().size(); i++) {
            UnboundSlot slot = (UnboundSlot) generate.getGeneratorOutput().get(i);
            Preconditions.checkState(slot.getNameParts().size() == 2,
                    "the size of nameParts of UnboundSlot in LogicalGenerate must be 2.");

            Expression boundGenerator = analyzer.analyze(generate.getGenerators().get(i));
            if (!(boundGenerator instanceof TableGeneratingFunction)) {
                throw new AnalysisException(boundGenerator.toSql() + " is not a TableGeneratingFunction");
            }
            Function generator = (Function) boundGenerator;
            boundGenerators.add(generator);

            Slot boundSlot = new SlotReference(slot.getNameParts().get(1), generator.getDataType(),
                    generator.nullable(), ImmutableList.of(slot.getNameParts().get(0)));
            outputSlots.add(boundSlot);
            // the boundSlot may has two situation:
            // 1. the expandColumnsAlias is not empty, we should use make boundSlot expand to multi alias
            // 2. the expandColumnsAlias is empty, we should use origin boundSlot
            if (generate.getExpandColumnAlias() != null && i < generate.getExpandColumnAlias().size()
                    && !CollectionUtils.isEmpty(generate.getExpandColumnAlias().get(i))) {
                // if the alias is not empty, we should bind it with struct_element as child expr with alias
                // struct_element(#expand_col#k, #k) as #k
                // struct_element(#expand_col#v, #v) as #v
                List<StructField> fields = ((StructType) boundSlot.getDataType()).getFields();
                for (int idx = 0; idx < fields.size(); ++idx) {
                    expandAlias.add(new Alias(new StructElement(
                            boundSlot, new StringLiteral(fields.get(idx).getName())),
                            generate.getExpandColumnAlias().get(i).get(idx),
                            slot.getQualifier()));
                }
            }
        }
        LogicalGenerate<Plan> ret = new LogicalGenerate<>(
                boundGenerators.build(), outputSlots.build(), generate.child());
        if (!expandAlias.isEmpty()) {
            // we need a project to deal with explode(map) to struct with field alias
            // project should contains: generator.child slot + expandAlias
            List<NamedExpression> allProjectSlots = generate.child().getOutput().stream()
                    .map(NamedExpression.class::cast)
                    .collect(Collectors.toList());
            allProjectSlots.addAll(expandAlias);
            return new LogicalProject<>(allProjectSlots, ret);
        }
        return ret;
    }

    private LogicalSetOperation bindSetOperation(LogicalSetOperation setOperation) {
        // check whether the left and right child output columns are the same
        if (setOperation.child(0).getOutput().size() != setOperation.child(1).getOutput().size()) {
            throw new AnalysisException("Operands have unequal number of columns:\n"
                    + "'" + setOperation.child(0).getOutput() + "' has "
                    + setOperation.child(0).getOutput().size() + " column(s)\n"
                    + "'" + setOperation.child(1).getOutput() + "' has "
                    + setOperation.child(1).getOutput().size() + " column(s)");
        }

        // INTERSECT and EXCEPT does not support ALL qualified
        if (setOperation.getQualifier() == Qualifier.ALL
                && (setOperation instanceof LogicalExcept || setOperation instanceof LogicalIntersect)) {
            throw new AnalysisException("INTERSECT and EXCEPT does not support ALL qualified");
        }
        // we need to do cast before set operation, because we maybe use these slot to do shuffle
        // so, we must cast it before shuffle to get correct hash code.
        List<List<NamedExpression>> childrenProjections = setOperation.collectChildrenProjections();
        int childrenProjectionSize = childrenProjections.size();
        Builder<List<SlotReference>> childrenOutputs = ImmutableList.builderWithExpectedSize(childrenProjectionSize);
        Builder<Plan> newChildren = ImmutableList.builderWithExpectedSize(childrenProjectionSize);
        for (int i = 0; i < childrenProjectionSize; i++) {
            Plan newChild;
            Plan child = setOperation.child(i);
            if (childrenProjections.get(i).stream().allMatch(SlotReference.class::isInstance)) {
                newChild = child;
            } else {
                List<NamedExpression> parentProject = childrenProjections.get(i);
                newChild = ProjectProcessor.tryProcessProject(parentProject, child)
                        .orElseGet(() -> new LogicalProject<>(parentProject, child));
            }
            newChildren.add(newChild);
            childrenOutputs.add((List<SlotReference>) (List) newChild.getOutput());
        }
        setOperation = setOperation.withChildrenAndTheirOutputs(newChildren.build(), childrenOutputs.build());
        List<NamedExpression> newOutputs = setOperation.buildNewOutputs();
        return setOperation.withNewOutputs(newOutputs);
    }

    @NotNull
    private LogicalOneRowRelation bindOneRowRelation(MatchingContext<OneRowRelation> ctx) {
        OneRowRelation oneRowRelation = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;
        SimpleExprAnalyzer analyzer = buildSimpleExprAnalyzer(oneRowRelation, cascadesContext, ImmutableList.of());
        List<NamedExpression> projects = analyzer.analyzeToList(oneRowRelation.getProjects());
        projects = adjustProjectionAggNullable(projects);
        return new LogicalOneRowRelation(oneRowRelation.getRelationId(), projects);
    }

    private LogicalPlan bindInlineTable(MatchingContext<InlineTable> ctx) {
        InlineTable inlineTable = ctx.root;
        // ensure all expressions are valid.
        List<List<NamedExpression>> constantExprsList = inlineTable.getConstantExprsList();
        List<LogicalPlan> relations
                = Lists.newArrayListWithCapacity(constantExprsList.size());
        for (int i = 0; i < constantExprsList.size(); i++) {
            List<NamedExpression> row = constantExprsList.get(i);
            for (NamedExpression constantExpr : row) {
                if (constantExpr instanceof DefaultValueSlot) {
                    throw new AnalysisException("Default expression"
                            + " can't exist in SELECT statement at row " + (i + 1));
                }
            }
            relations.add(new UnboundOneRowRelation(StatementScopeIdGenerator.newRelationId(), row));
        }
        // construct union all tree
        return LogicalPlanBuilder.reduceToLogicalPlanTree(0, relations.size() - 1, relations, Qualifier.ALL);
    }

    private LogicalHaving<Plan> bindHaving(MatchingContext<LogicalHaving<Plan>> ctx) {
        LogicalHaving<Plan> having = ctx.root;
        Plan childPlan = having.child();
        CascadesContext cascadesContext = ctx.cascadesContext;

        // bind slot by child.output first
        Scope childOutput = toScope(cascadesContext, childPlan.getOutput());
        // then bind slot by child.children.output
        Supplier<Scope> childChildrenOutput = Suppliers.memoize(() ->
                toScope(cascadesContext, PlanUtils.fastGetChildrenOutputs(childPlan.children()))
        );
        LogicalHaving<Plan> boundHaving = bindHavingByScopes(having, having.child(),
                cascadesContext, childOutput, childChildrenOutput);
        if (!SqlModeHelper.hasOnlyFullGroupBy() && childPlan instanceof LogicalProject) {
            // ATTN: process having(project) that have aggregate function in having
            LogicalProject<?> project = (LogicalProject<?>) childPlan;
            List<AggregateFunction> aggFuncs = CollectNonWindowedAggFuncs.collect(boundHaving.getConjuncts());
            if (!aggFuncs.isEmpty()) {
                Map<Expression, Expression> replaceMap = Maps.newHashMap();
                for (AggregateFunction aggFunc : aggFuncs) {
                    // ATTN: this is a little trick here. since replace check replace successful with equal operator
                    //  see: org.apache.doris.nereids.trees.TreeNode.rewriteDownShortCircuit
                    //  here, we generate a new aggFunc to replace to avoid rewrite its child.
                    //  because we do not want to replace agg func agg(child) to agg(any_value(child))
                    replaceMap.put(aggFunc, aggFunc.withChildren(aggFunc.children()));
                }
                Builder<NamedExpression> boundProjectionsBuilder
                        = ImmutableList.builderWithExpectedSize(project.getProjects().size());
                for (NamedExpression expr : project.getProjects()) {
                    if (expr instanceof SlotReference) {
                        Alias alias = new Alias(new AnyValue(expr), expr.getName());
                        boundProjectionsBuilder.add(alias);
                        replaceMap.put(expr, alias);
                    } else {
                        boundProjectionsBuilder.add(expr);
                    }
                }
                Plan newChildPlan = project.withProjects(boundProjectionsBuilder.build());
                ImmutableSet.Builder<Expression> newConjunctsBuilder = ImmutableSet.builder();
                for (Expression conjunct : boundHaving.getConjuncts()) {
                    newConjunctsBuilder.add(ExpressionUtils.replace(conjunct, replaceMap));
                }
                boundHaving = boundHaving.withConjunctsAndChild(newConjunctsBuilder.build(), newChildPlan);
            }
        }
        return boundHaving;
    }

    private LogicalHaving<Plan> bindHavingAggregate(
            MatchingContext<LogicalHaving<Aggregate<Plan>>> ctx) {
        LogicalHaving<Aggregate<Plan>> having = ctx.root;
        Aggregate<Plan> aggregate = having.child();
        CascadesContext cascadesContext = ctx.cascadesContext;

        // keep same behavior as mysql
        Supplier<CustomSlotBinderAnalyzer> bindByAggChild = Suppliers.memoize(() -> {
            Scope aggChildOutputScope
                    = toScope(cascadesContext, PlanUtils.fastGetChildrenOutputs(aggregate.children()));
            return (analyzer, unboundSlot) -> analyzer.bindSlotByScope(unboundSlot, aggChildOutputScope);
        });

        Scope aggOutputScope = toScope(cascadesContext, aggregate.getOutput());
        Supplier<CustomSlotBinderAnalyzer> bindByGroupByThenAggOutputThenAggChild = Suppliers.memoize(() -> {
            List<Expression> groupByExprs = aggregate.getGroupByExpressions();
            ImmutableList.Builder<Slot> groupBySlots
                    = ImmutableList.builderWithExpectedSize(groupByExprs.size());
            for (Expression groupBy : groupByExprs) {
                if (groupBy instanceof Slot) {
                    groupBySlots.add((Slot) groupBy);
                }
            }
            Scope groupBySlotsScope = toScope(cascadesContext, groupBySlots.build());

            return (analyzer, unboundSlot) -> {
                List<Slot> boundInGroupBy = analyzer.bindSlotByScope(unboundSlot, groupBySlotsScope);
                if (!boundInGroupBy.isEmpty()) {
                    return ImmutableList.of(boundInGroupBy.get(0));
                }

                List<Slot> boundInAggOutput = analyzer.bindSlotByScope(unboundSlot, aggOutputScope);
                if (!boundInAggOutput.isEmpty()) {
                    return ImmutableList.of(boundInAggOutput.get(0));
                }

                List<? extends Expression> expressions = bindByAggChild.get().bindSlot(analyzer, unboundSlot);
                return expressions.isEmpty() ? expressions : ImmutableList.of(expressions.get(0));
            };
        });

        FunctionRegistry functionRegistry = cascadesContext.getConnectContext().getEnv().getFunctionRegistry();
        ExpressionAnalyzer havingAnalyzer = new ExpressionAnalyzer(having, aggOutputScope, cascadesContext,
                false, true) {
            private boolean currentIsInAggregateFunction;

            @Override
            public Expression visitAggregateFunction(AggregateFunction aggregateFunction,
                    ExpressionRewriteContext context) {
                if (!currentIsInAggregateFunction) {
                    currentIsInAggregateFunction = true;
                    try {
                        return super.visitAggregateFunction(aggregateFunction, context);
                    } finally {
                        currentIsInAggregateFunction = false;
                    }
                } else {
                    return super.visitAggregateFunction(aggregateFunction, context);
                }
            }

            @Override
            public Expression visitUnboundFunction(UnboundFunction unboundFunction, ExpressionRewriteContext context) {
                if (!currentIsInAggregateFunction && isAggregateFunction(unboundFunction, functionRegistry)) {
                    currentIsInAggregateFunction = true;
                    try {
                        return super.visitUnboundFunction(unboundFunction, context);
                    } finally {
                        currentIsInAggregateFunction = false;
                    }
                } else {
                    return super.visitUnboundFunction(unboundFunction, context);
                }
            }

            @Override
            protected List<? extends Expression> bindSlotByThisScope(UnboundSlot unboundSlot) {
                if (currentIsInAggregateFunction) {
                    return bindByAggChild.get().bindSlot(this, unboundSlot);
                } else {
                    return bindByGroupByThenAggOutputThenAggChild.get().bindSlot(this, unboundSlot);
                }
            }
        };

        Set<Expression> havingExprs = having.getConjuncts();
        ImmutableSet.Builder<Expression> analyzedHaving = ImmutableSet.builderWithExpectedSize(havingExprs.size());
        ExpressionRewriteContext rewriteContext = new ExpressionRewriteContext(cascadesContext);
        for (Expression expression : havingExprs) {
            analyzedHaving.add(havingAnalyzer.analyze(expression, rewriteContext));
        }

        return new LogicalHaving<>(analyzedHaving.build(), having.child());
    }

    private LogicalHaving<Plan> bindHavingByScopes(
            LogicalHaving<? extends Plan> having, Plan child,
            CascadesContext cascadesContext, Scope defaultScope, Supplier<Scope> backupScope) {

        SimpleExprAnalyzer analyzer = buildCustomSlotBinderAnalyzer(
                having, cascadesContext, defaultScope, false, true,
                (self, unboundSlot) -> {
                    List<Slot> slots = self.bindSlotByScope(unboundSlot, defaultScope);
                    if (!slots.isEmpty()) {
                        return slots;
                    }
                    return self.bindSlotByScope(unboundSlot, backupScope.get());
                });
        ImmutableSet.Builder<Expression> boundConjuncts
                = ImmutableSet.builderWithExpectedSize(having.getConjuncts().size());
        for (Expression conjunct : having.getConjuncts()) {
            conjunct = analyzer.analyze(conjunct);
            conjunct = TypeCoercionUtils.castIfNotSameType(conjunct, BooleanType.INSTANCE);
            boundConjuncts.add(conjunct);
        }
        checkIfOutputAliasNameDuplicatedForGroupBy(boundConjuncts.build(),
                child instanceof LogicalProject ? ((LogicalProject<?>) child).getOutputs() : child.getOutput());
        return new LogicalHaving<>(boundConjuncts.build(), child);
    }

    private LogicalSort<LogicalSetOperation> bindSortWithSetOperation(
            MatchingContext<LogicalSort<LogicalSetOperation>> ctx) {
        LogicalSort<LogicalSetOperation> sort = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;

        List<Slot> childOutput = sort.child().getOutput();
        SimpleExprAnalyzer analyzer = buildSimpleExprAnalyzer(sort, cascadesContext, sort.children());
        Builder<OrderKey> boundKeys = ImmutableList.builderWithExpectedSize(sort.getOrderKeys().size());
        for (OrderKey orderKey : sort.getOrderKeys()) {
            Expression boundKey = bindWithOrdinal(orderKey.getExpr(), analyzer, childOutput);
            boundKeys.add(orderKey.withExpression(boundKey));
        }
        return new LogicalSort<>(boundKeys.build(), sort.child());
    }

    private LogicalJoin<Plan, Plan> bindJoin(MatchingContext<LogicalJoin<Plan, Plan>> ctx) {
        LogicalJoin<Plan, Plan> join = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;

        checkConflictAlias(join);

        SimpleExprAnalyzer analyzer = buildSimpleExprAnalyzer(join, cascadesContext, join.children());

        Builder<Expression> hashJoinConjuncts = ImmutableList.builderWithExpectedSize(
                join.getHashJoinConjuncts().size());
        for (Expression hashJoinConjunct : join.getHashJoinConjuncts()) {
            hashJoinConjunct = analyzer.analyze(hashJoinConjunct);
            hashJoinConjunct = TypeCoercionUtils.castIfNotSameType(hashJoinConjunct, BooleanType.INSTANCE);
            hashJoinConjuncts.add(hashJoinConjunct);
        }
        Builder<Expression> otherJoinConjuncts = ImmutableList.builderWithExpectedSize(
                join.getOtherJoinConjuncts().size());
        for (Expression otherJoinConjunct : join.getOtherJoinConjuncts()) {
            otherJoinConjunct = analyzer.analyze(otherJoinConjunct);
            otherJoinConjunct = TypeCoercionUtils.castIfNotSameType(otherJoinConjunct, BooleanType.INSTANCE);
            otherJoinConjuncts.add(otherJoinConjunct);
        }
        return new LogicalJoin<>(join.getJoinType(),
                hashJoinConjuncts.build(), otherJoinConjuncts.build(),
                join.getDistributeHint(), join.getMarkJoinSlotReference(), join.getExceptAsteriskOutputs(),
                join.children(), null);
    }

    private void checkConflictAlias(Plan plan) {
        Set<String> existsTableNames = Sets.newLinkedHashSet();
        Consumer<String> checkAlias = tableAliasName -> {
            if (!existsTableNames.add(tableAliasName)) {
                String tableName = tableAliasName.substring(tableAliasName.lastIndexOf('.') + 1);
                throw new AnalysisException("Not unique table/alias: '" + tableName + "'");
            }
        };

        boolean stopCheckChildren = true;
        plan.foreach(p -> {
            if (p instanceof LogicalSubQueryAlias) {
                String alias = ((LogicalSubQueryAlias<?>) p).getAlias();
                String dbName = getDbName(p.children().get(0));
                String result = dbName + "." + alias;
                checkAlias.accept(result);
                return stopCheckChildren;

            } else if (p instanceof LogicalCatalogRelation) {
                String table = ((LogicalCatalogRelation) p).qualifiedName();
                checkAlias.accept(table);
                return stopCheckChildren;
            } else {
                return !stopCheckChildren;
            }
        });
    }

    private String getDbName(Plan plan) {
        if (plan instanceof LogicalCatalogRelation) {
            return ((LogicalCatalogRelation) plan).qualifiedName().split("\\.")[0]
                    + ((LogicalCatalogRelation) plan).qualifiedName().split("\\.")[1];
        } else if (plan instanceof LogicalSubQueryAlias) {
            return ((LogicalSubQueryAlias<?>) plan).getQualifier().get(0)
                    + ((LogicalSubQueryAlias<?>) plan).getQualifier().get(1);

        } else {
            return "default-catalog" + "default-db";
        }
    }

    private LogicalPlan bindUsingJoin(MatchingContext<LogicalUsingJoin<Plan, Plan>> ctx) {
        LogicalUsingJoin<Plan, Plan> using = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;
        List<Expression> unboundHashJoinConjunct = using.getUsingSlots();

        Scope leftScope = toScope(cascadesContext, using.left().getOutput(), using.left().getAsteriskOutput());
        Scope rightScope = toScope(cascadesContext, using.right().getOutput(), using.right().getAsteriskOutput());
        ExpressionRewriteContext rewriteContext = new ExpressionRewriteContext(cascadesContext);

        Builder<Expression> hashEqExprs = ImmutableList.builderWithExpectedSize(unboundHashJoinConjunct.size());
        List<Slot> rightConjunctsSlots = Lists.newArrayList();
        for (Expression usingColumn : unboundHashJoinConjunct) {
            ExpressionAnalyzer leftExprAnalyzer = new ExpressionAnalyzer(
                    using, leftScope, cascadesContext, true, false);
            Expression usingLeftSlot = leftExprAnalyzer.analyze(usingColumn, rewriteContext);

            ExpressionAnalyzer rightExprAnalyzer = new ExpressionAnalyzer(
                    using, rightScope, cascadesContext, true, false);
            Expression usingRightSlot = rightExprAnalyzer.analyze(usingColumn, rewriteContext);
            rightConjunctsSlots.add((Slot) usingRightSlot);
            hashEqExprs.add(new EqualTo(usingLeftSlot, usingRightSlot));
        }

        return new LogicalJoin<>(
                using.getJoinType() == JoinType.CROSS_JOIN ? JoinType.INNER_JOIN : using.getJoinType(),
                hashEqExprs.build(), ImmutableList.of(),
                using.getDistributeHint(), Optional.empty(), rightConjunctsSlots,
                using.children(), null);
    }

    private Plan bindProject(MatchingContext<LogicalProject<Plan>> ctx) {
        LogicalProject<Plan> project = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;

        SimpleExprAnalyzer analyzer = buildSimpleExprAnalyzer(project, cascadesContext, project.children());
        Builder<NamedExpression> boundProjectionsBuilder
                = ImmutableList.builderWithExpectedSize(project.getProjects().size());
        StatementContext statementContext = ctx.statementContext;
        for (Expression expression : project.getProjects()) {
            Expression expr = analyzer.analyze(expression);
            if (!(expr instanceof BoundStar)) {
                boundProjectionsBuilder.add((NamedExpression) expr);
            } else {
                UnboundStar unboundStar = (UnboundStar) expression;
                List<NamedExpression> excepts = unboundStar.getExceptedSlots();
                Set<NamedExpression> boundExcepts = Suppliers.memoize(() -> analyzer.analyzeToSet(excepts)).get();
                BoundStar boundStar = (BoundStar) expr;

                List<Slot> slots = exceptStarSlots(boundExcepts, boundStar);

                List<NamedExpression> replaces = unboundStar.getReplacedAlias();
                if (!replaces.isEmpty()) {
                    final Map<Expression, Expression> replaceMap = new HashMap<>();
                    final Set<Expression> replaced = new HashSet<>();
                    Supplier<List<NamedExpression>> boundReplaces = Suppliers.memoize(
                            () -> analyzer.analyzeToList(replaces));
                    for (NamedExpression replace : boundReplaces.get()) {
                        Preconditions.checkArgument(replace instanceof Alias);
                        Alias alias = (Alias) replace;
                        UnboundSlot unboundSlot = new UnboundSlot(alias.getName());
                        Expression slot = analyzer.analyze(unboundSlot);
                        if (replaceMap.containsKey(slot)) {
                            throw new AnalysisException("Duplicate replace column name: " + alias.getName());
                        }
                        replaceMap.put(slot, alias);
                    }

                    Collection c = CollectionUtils.intersection(boundExcepts, replaceMap.keySet());
                    if (!c.isEmpty()) {
                        throw new AnalysisException("Replace column name: " + c + " is in excepts");
                    }
                    for (Slot s : slots) {
                        Expression e = ExpressionUtils.replace(s, replaceMap);
                        if (s != e) {
                            replaced.add(s);
                        }
                        boundProjectionsBuilder.add((NamedExpression) e);
                    }

                    if (replaced.size() != replaceMap.size()) {
                        replaceMap.keySet().removeAll(replaced);
                        throw new AnalysisException("Invalid replace column name: " + replaceMap.keySet());
                    }
                } else {
                    boundProjectionsBuilder.addAll(slots);
                }

                // for create view stmt expand star
                List<Slot> slotsForLambda = slots;
                unboundStar.getIndexInSqlString().ifPresent(pair -> {
                    statementContext.addIndexInSqlToString(pair, toSqlWithBackquote(slotsForLambda));
                });
            }
        }
        List<NamedExpression> projects = adjustProjectionAggNullable(boundProjectionsBuilder.build());
        return project.withProjects(projects);
    }

    private List<NamedExpression> adjustProjectionAggNullable(List<NamedExpression> expressions) {
        boolean hasAggregation = expressions.stream()
                .anyMatch(expr -> expr.accept(ExpressionVisitors.CONTAINS_AGGREGATE_CHECKER, null));
        if (!hasAggregation) {
            return expressions;
        }
        boolean hasOnlyFullGroupBy = SqlModeHelper.hasOnlyFullGroupBy();
        Builder<NamedExpression> newExpressionsBuilder = ImmutableList.builderWithExpectedSize(expressions.size());
        for (NamedExpression expr : expressions) {
            expr = (NamedExpression) expr.rewriteDownShortCircuit(e -> {
                // for `select sum(a) from t`, sum(a) is nullable
                if (e instanceof NullableAggregateFunction) {
                    return ((NullableAggregateFunction) e).withAlwaysNullable(true);
                }
                return e;
            });
            if (!hasOnlyFullGroupBy && expr instanceof SlotReference) {
                expr = new Alias(expr, expr.getName());
            }
            newExpressionsBuilder.add(expr);
        }
        return newExpressionsBuilder.build();
    }

    private Plan bindLoadProject(MatchingContext<LogicalLoadProject<Plan>> ctx) {
        LogicalLoadProject<Plan> project = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;

        SimpleExprAnalyzer analyzer = buildSimpleExprAnalyzer(project, cascadesContext, project.children());
        Builder<NamedExpression> boundProjections = ImmutableList.builderWithExpectedSize(project.getProjects().size());
        StatementContext statementContext = ctx.statementContext;
        for (Expression expression : project.getProjects()) {
            Expression expr = analyzer.analyze(expression);
            if (!(expr instanceof BoundStar)) {
                boundProjections.add((NamedExpression) expr);
            } else {
                UnboundStar unboundStar = (UnboundStar) expression;
                List<NamedExpression> excepts = unboundStar.getExceptedSlots();
                Set<NamedExpression> boundExcepts = Suppliers.memoize(() -> analyzer.analyzeToSet(excepts)).get();
                BoundStar boundStar = (BoundStar) expr;

                List<Slot> slots = exceptStarSlots(boundExcepts, boundStar);

                List<NamedExpression> replaces = unboundStar.getReplacedAlias();
                if (!replaces.isEmpty()) {
                    final Map<Expression, Expression> replaceMap = new HashMap<>();
                    final Set<Expression> replaced = new HashSet<>();
                    Supplier<List<NamedExpression>> boundReplaces = Suppliers.memoize(
                            () -> analyzer.analyzeToList(replaces));
                    for (NamedExpression replace : boundReplaces.get()) {
                        Preconditions.checkArgument(replace instanceof Alias);
                        Alias alias = (Alias) replace;
                        UnboundSlot unboundSlot = new UnboundSlot(alias.getName());
                        Expression slot = analyzer.analyze(unboundSlot);
                        if (replaceMap.containsKey(slot)) {
                            throw new AnalysisException("Duplicate replace column name: " + alias.getName());
                        }
                        replaceMap.put(slot, alias);
                    }

                    Collection c = CollectionUtils.intersection(boundExcepts, replaceMap.keySet());
                    if (!c.isEmpty()) {
                        throw new AnalysisException("Replace column name: " + c + " is in excepts");
                    }
                    for (Slot s : slots) {
                        Expression e = ExpressionUtils.replace(s, replaceMap);
                        if (s != e) {
                            replaced.add(s);
                        }
                        boundProjections.add((NamedExpression) e);
                    }

                    if (replaced.size() != replaceMap.size()) {
                        replaceMap.keySet().removeAll(replaced);
                        throw new AnalysisException("Invalid replace column name: " + replaceMap.keySet());
                    }
                } else {
                    boundProjections.addAll(slots);
                }

                // for create view stmt expand star
                List<Slot> slotsForLambda = slots;
                unboundStar.getIndexInSqlString().ifPresent(pair -> {
                    statementContext.addIndexInSqlToString(pair, toSqlWithBackquote(slotsForLambda));
                });
            }
        }
        return project.withProjects(boundProjections.build());
    }

    private Plan bindFilter(MatchingContext<LogicalFilter<Plan>> ctx) {
        LogicalFilter<Plan> filter = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;

        SimpleExprAnalyzer analyzer = buildSimpleExprAnalyzer(filter, cascadesContext, filter.children());
        ImmutableSet.Builder<Expression> boundConjuncts = ImmutableSet.builderWithExpectedSize(
                filter.getConjuncts().size());
        boolean changed = false;
        for (Expression conjunct : filter.getConjuncts()) {
            Expression boundConjunct = analyzer.analyze(conjunct);
            boundConjunct = TypeCoercionUtils.castIfNotSameType(boundConjunct, BooleanType.INSTANCE);
            changed |= boundConjunct != conjunct;
            boundConjuncts.add(boundConjunct);
        }
        if (!changed) {
            return filter;
        }
        return new LogicalFilter<>(boundConjuncts.build(), filter.child());
    }

    private Plan bindPreFilter(MatchingContext<LogicalPreFilter<Plan>> ctx) {
        LogicalPreFilter<Plan> filter = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;

        SimpleExprAnalyzer analyzer = buildSimpleExprAnalyzer(filter, cascadesContext, filter.children());
        ImmutableSet.Builder<Expression> boundConjuncts = ImmutableSet.builderWithExpectedSize(
                filter.getConjuncts().size());
        for (Expression conjunct : filter.getConjuncts()) {
            Expression boundConjunct = analyzer.analyze(conjunct);
            boundConjunct = TypeCoercionUtils.castIfNotSameType(boundConjunct, BooleanType.INSTANCE);
            boundConjuncts.add(boundConjunct);
        }
        return new LogicalPreFilter<>(boundConjuncts.build(), filter.child());
    }

    /**
     * there a dup table sales
     * CREATE TABLE sales (
     *    year INT,
     *    country STRING,
     *    product STRING,
     *    profit INT
     * )
     * DISTRIBUTED BY HASH(`year`)
     * PROPERTIES (
     * "replication_num" = "1"
     * );
     * 1.qualify -> project
     * for example :
     * select year + 1 as year from sales qualify row_number() over (order by year, country) = 1;
     * We are binding the year field of table sales. Instead of renaming year
     * -----------------------------------------------------------------------------------------------------------------
     * 2.qualify -> project(distinct)
     * for example:
     * select distinct year + 1, country from sales qualify row_number() over (order by year + 1) > 1;
     * We are binding the year field of table sales.
     * -----------------------------------------------------------------------------------------------------------------
     * 3.qualify -> project(distinct) -> agg
     * for example:
     * select distinct year + 1 as year from sales group by year qualify row_number() over (order by year) = 1;
     * We are binding the year field of group by output. Instead of renaming year
     * -----------------------------------------------------------------------------------------------------------------
     * 4.qualify -> project(distinct) -> having -> agg
     * for example:
     * select distinct year,country from sales group by year,country having year > 2000
     * qualify row_number() over (order by year + 1) > 1;
     * We are binding the year field of group output.
     *-----------------------------------------------------------------------------------------------------------------
     * Note: For the query without agg, we first bind slot from the child of the project.
     * If it cannot be bound in the child, then bind slot from the project.
     * If query with agg, we bind slot from the group by first. if not then bind slot from the group output
     * or not bind slot from the agg child output finally.
     */
    private Plan bindQualifyProject(MatchingContext<LogicalQualify<LogicalProject<Plan>>> ctx) {
        LogicalQualify<LogicalProject<Plan>> qualify = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;
        LogicalProject<Plan> project = qualify.child();
        ImmutableSet.Builder<Expression> boundConjuncts = ImmutableSet.builderWithExpectedSize(
                qualify.getConjuncts().size());
        if (project.child() instanceof Aggregate) {
            Aggregate<Plan> aggregate = (Aggregate<Plan>) project.child();
            bindQualifyByAggregate(aggregate, cascadesContext, qualify, boundConjuncts);
        } else if (project.child() instanceof LogicalHaving) {
            LogicalHaving<Plan> having = (LogicalHaving<Plan>) project.child();
            if (having.child() instanceof Aggregate) {
                Aggregate<Plan> aggregate = (Aggregate<Plan>) having.child();
                bindQualifyByAggregate(aggregate, cascadesContext, qualify, boundConjuncts);
            } else {
                throw new AnalysisException("unknown query structure");
            }
        } else {
            bindQualifyByProject(project, cascadesContext, qualify, boundConjuncts);
        }
        return new LogicalQualify<>(boundConjuncts.build(), qualify.child());
    }

    /**
     * 1.qualify -> having -> agg
     * for example:
     * select country, sum(profit) as total, row_number() over (order by country) as rk from sales where year >= 2000
     * group by country having sum(profit) > 100 qualify rk = 1
     * We are binding the country field from group by.
     * -----------------------------------------------------------------------------------------------------------------
     * 2.qualify -> having -> project
     * for example:
     * select year, country, profit, row_number() over (partition by year, country order by profit desc) as rk from
     * (select * from sales) a where year >= 2000 having profit > 200 qualify rk = 1 order by profit,country limit 3
     * We are binding year/country/profit from sales
     * -----------------------------------------------------------------------------------------------------------------
     * 3.qualify -> having -> project(distinct)
     * for example:
     * select distinct year + 1 as year from sales qualify row_number() over (order by year) = 1;
     * we are binding year from sales. Instead of renaming year
     */
    private Plan bindQualifyHaving(MatchingContext<LogicalQualify<LogicalHaving<Plan>>> ctx) {
        LogicalQualify<LogicalHaving<Plan>> qualify = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;
        LogicalHaving<Plan> having = qualify.child();
        ImmutableSet.Builder<Expression> boundConjuncts = ImmutableSet.builderWithExpectedSize(
                qualify.getConjuncts().size());
        if (having.child() instanceof Aggregate) {
            bindQualifyByAggregate((Aggregate<? extends Plan>) having.child(), cascadesContext, qualify,
                    boundConjuncts);
        } else {
            bindQualifyByProject((LogicalProject<? extends Plan>) having.child(), cascadesContext, qualify,
                    boundConjuncts);
        }
        return new LogicalQualify<>(boundConjuncts.build(), qualify.child());
    }

    /**
     * qualify -> agg
     * for example:
     * select country, sum(profit) as total, row_number() over (order by country) as rk from sales qualify rk > 1
     * we are binding the country field from group by.
     */
    private Plan bindQualifyAggregate(MatchingContext<LogicalQualify<Aggregate<Plan>>> ctx) {
        LogicalQualify<Aggregate<Plan>> qualify = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;
        Aggregate<Plan> aggregate = qualify.child();
        ImmutableSet.Builder<Expression> boundConjuncts = ImmutableSet.builderWithExpectedSize(
                qualify.getConjuncts().size());
        bindQualifyByAggregate(aggregate, cascadesContext, qualify, boundConjuncts);
        return new LogicalQualify<>(boundConjuncts.build(), qualify.child());
    }

    private void bindQualifyByProject(LogicalProject<? extends Plan> project, CascadesContext cascadesContext,
                                    LogicalQualify<? extends Plan> qualify,
                                    ImmutableSet.Builder<Expression> boundConjuncts) {
        Supplier<Scope> defaultScope = Suppliers.memoize(() ->
                toScope(cascadesContext, PlanUtils.fastGetChildrenOutputs(project.children()))
        );
        Scope backupScope = toScope(cascadesContext, project.getOutput());

        SimpleExprAnalyzer analyzer = buildCustomSlotBinderAnalyzer(
                qualify, cascadesContext, defaultScope.get(), true, true,
                (self, unboundSlot) -> {
                List<Slot> slots = self.bindSlotByScope(unboundSlot, defaultScope.get());
                if (!slots.isEmpty()) {
                    return slots;
                }
                return self.bindSlotByScope(unboundSlot, backupScope);
                });

        for (Expression conjunct : qualify.getConjuncts()) {
            conjunct = analyzer.analyze(conjunct);
            conjunct = TypeCoercionUtils.castIfNotSameType(conjunct, BooleanType.INSTANCE);
            boundConjuncts.addAll(ExpressionUtils.extractConjunctionToSet(conjunct));
        }
    }

    private void bindQualifyByAggregate(Aggregate<? extends Plan> aggregate, CascadesContext cascadesContext,
                                      LogicalQualify<? extends Plan> qualify,
                                      ImmutableSet.Builder<Expression> boundConjuncts) {
        Supplier<CustomSlotBinderAnalyzer> bindByAggChild = Suppliers.memoize(() -> {
            Scope aggChildOutputScope
                    = toScope(cascadesContext, PlanUtils.fastGetChildrenOutputs(aggregate.children()));
            return (analyzer, unboundSlot) -> analyzer.bindSlotByScope(unboundSlot, aggChildOutputScope);
        });
        Scope aggOutputScope = toScope(cascadesContext, aggregate.getOutput());
        Supplier<CustomSlotBinderAnalyzer> bindByGroupByThenAggOutputThenAggChildOutput = Suppliers.memoize(() -> {
            List<Expression> groupByExprs = aggregate.getGroupByExpressions();
            ImmutableList.Builder<Slot> groupBySlots = ImmutableList.builderWithExpectedSize(groupByExprs.size());
            for (Expression groupBy : groupByExprs) {
                if (groupBy instanceof Slot) {
                    groupBySlots.add((Slot) groupBy);
                }
            }
            Scope groupBySlotsScope = toScope(cascadesContext, groupBySlots.build());

            return (analyzer, unboundSlot) -> {
                List<Slot> boundInGroupBy = analyzer.bindSlotByScope(unboundSlot, groupBySlotsScope);
                if (!boundInGroupBy.isEmpty()) {
                    return ImmutableList.of(boundInGroupBy.get(0));
                }
                List<Slot> boundInAggOutput = analyzer.bindSlotByScope(unboundSlot, aggOutputScope);
                if (!boundInAggOutput.isEmpty()) {
                    return ImmutableList.of(boundInAggOutput.get(0));
                }
                List<? extends Expression> expressions = bindByAggChild.get().bindSlot(analyzer, unboundSlot);
                return expressions.isEmpty() ? expressions : ImmutableList.of(expressions.get(0));
            };
        });

        ExpressionAnalyzer qualifyAnalyzer = new ExpressionAnalyzer(qualify, aggOutputScope, cascadesContext,
                true, true) {
            @Override
            protected List<? extends Expression> bindSlotByThisScope(UnboundSlot unboundSlot) {
                return bindByGroupByThenAggOutputThenAggChildOutput.get().bindSlot(this, unboundSlot);
            }
        };

        ExpressionRewriteContext rewriteContext = new ExpressionRewriteContext(cascadesContext);
        for (Expression expression : qualify.getConjuncts()) {
            Expression boundConjunct = qualifyAnalyzer.analyze(expression, rewriteContext);
            boundConjunct = TypeCoercionUtils.castIfNotSameType(boundConjunct, BooleanType.INSTANCE);
            boundConjuncts.addAll(ExpressionUtils.extractConjunctionToSet(boundConjunct));
        }
    }

    private List<Slot> exceptStarSlots(Set<NamedExpression> boundExcepts, BoundStar boundStar) {
        List<Slot> slots = boundStar.getSlots();
        if (!boundExcepts.isEmpty()) {
            slots = Utils.filterImmutableList(slots, slot -> !boundExcepts.contains(slot));
            if (slots.isEmpty()) {
                throw new AnalysisException("All slots in * EXCEPT clause are excepted");
            }
        }
        return slots;
    }

    private Plan bindAggregate(MatchingContext<LogicalAggregate<Plan>> ctx) {
        LogicalAggregate<Plan> agg = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;

        SimpleExprAnalyzer aggOutputAnalyzer = buildSimpleExprAnalyzer(agg, cascadesContext, agg.children());
        List<NamedExpression> boundAggOutput = aggOutputAnalyzer.analyzeToList(agg.getOutputExpressions());
        List<NamedExpression> boundProjections = new ArrayList<>(boundAggOutput.size());
        for (int i = 0; i < boundAggOutput.size(); i++) {
            NamedExpression output = boundAggOutput.get(i);
            if (output instanceof BoundStar) {
                UnboundStar unboundStar = (UnboundStar) agg.getOutputExpression(i);
                if (!unboundStar.getReplacedAlias().isEmpty()) {
                    // If user use * replace in agg, witch slot the group by clause should reference is ambiguous.
                    // For example, select * replace (col % 2 as col) from t group by col
                    // The sql should be rewritten like follows:
                    // select * except (col), col % 2 as col1 from t group by ..., col1
                    // or
                    // select * except (col), col % 2 as col1 from t group by ..., col -- group by origin col in t
                    throw new AnalysisException("* REPLACE in agg clause is not supported, use * EXCEPT instead");
                }
                List<NamedExpression> excepts = unboundStar.getExceptedSlots();
                Set<NamedExpression> boundExcepts = Suppliers.memoize(
                        () -> aggOutputAnalyzer.analyzeToSet(excepts)).get();
                List<Slot> slots = exceptStarSlots(boundExcepts, (BoundStar) output);
                boundProjections.addAll(slots);
            } else {
                boundProjections.add(output);
            }
        }

        Supplier<Scope> aggOutputScopeWithoutAggFun =
                buildAggOutputScopeWithoutAggFun(boundProjections, cascadesContext);
        List<Expression> boundGroupBy = bindGroupBy(
                agg, agg.getGroupByExpressions(), boundProjections, aggOutputScopeWithoutAggFun, cascadesContext);
        return agg.withGroupByAndOutput(boundGroupBy, processNonStandardAggregate(boundProjections, boundGroupBy));
    }

    private Plan bindRepeat(MatchingContext<LogicalRepeat<Plan>> ctx) {
        LogicalRepeat<Plan> repeat = ctx.root;
        CascadesContext cascadesContext = ctx.cascadesContext;

        SimpleExprAnalyzer repeatOutputAnalyzer = buildSimpleExprAnalyzer(repeat, cascadesContext, repeat.children());
        List<NamedExpression> boundRepeatOutput = repeatOutputAnalyzer.analyzeToList(repeat.getOutputExpressions());
        Supplier<Scope> aggOutputScopeWithoutAggFun =
                buildAggOutputScopeWithoutAggFun(boundRepeatOutput, cascadesContext);

        Builder<List<Expression>> boundGroupingSetsBuilder =
                ImmutableList.builderWithExpectedSize(repeat.getGroupingSets().size());
        Set<Expression> flatBoundGroupingSet = Sets.newHashSet();
        for (List<Expression> groupingSet : repeat.getGroupingSets()) {
            List<Expression> boundGroupingSet = bindGroupBy(
                    repeat, groupingSet, boundRepeatOutput, aggOutputScopeWithoutAggFun, cascadesContext);
            boundGroupingSetsBuilder.add(boundGroupingSet);
            flatBoundGroupingSet.addAll(boundGroupingSet);
        }
        List<List<Expression>> boundGroupingSets = boundGroupingSetsBuilder.build();
        List<NamedExpression> nullableOutput = PlanUtils.adjustNullableForRepeat(boundGroupingSets, boundRepeatOutput);
        for (List<Expression> groupingSet : boundGroupingSets) {
            checkIfOutputAliasNameDuplicatedForGroupBy(groupingSet, nullableOutput);
        }

        // check all GroupingScalarFunction inputSlots must be from groupingExprs
        Set<Slot> groupingExprs = boundGroupingSets.stream()
                .flatMap(Collection::stream).map(Expression::getInputSlots)
                .flatMap(Collection::stream).collect(Collectors.toSet());
        Set<GroupingScalarFunction> groupingScalarFunctions = ExpressionUtils
                .collect(nullableOutput, GroupingScalarFunction.class::isInstance);
        for (GroupingScalarFunction function : groupingScalarFunctions) {
            if (!groupingExprs.containsAll(function.getInputSlots())) {
                throw new AnalysisException("Column in " + function.getName()
                        + " does not exist in GROUP BY clause.");
            }
        }

        return repeat.withGroupSetsAndOutput(boundGroupingSets,
                processNonStandardAggregate(nullableOutput, flatBoundGroupingSet));
    }

    /**
     * for support non-standard aggregate, such as SELECT c1, c2 FROM t GROUP BY c1.
     * we around an extra Alias on SlotReference in output expression list
     * to avoid ExprId conflict. Because we will do a transform later like:
     * <p>
     * Repeat([c1#1, c2#2 as c2#3], [[c1#1], [c3#4]])
     * ---
     * Project(c1#1, c2#3)
     * +-- Aggregate([c1#1, any_value(c2#2) as c2#3], [c1#1])
     *     +-- Project(c1#1, c2#2)
     *
     * @param originalProjections original projections in aggregation
     * @param groupingExprs all expressions for group by key
     *
     * @return output with wrapped scalar slots
     */
    private List<NamedExpression> processNonStandardAggregate(
            List<NamedExpression> originalProjections, Collection<Expression> groupingExprs) {
        if (SqlModeHelper.hasOnlyFullGroupBy()) {
            return originalProjections;
        } else {

            ImmutableList.Builder<NamedExpression> finalProjectionsBuilder = ImmutableList.builder();
            for (NamedExpression projection : originalProjections) {
                // we do a trick here
                if (projection instanceof SlotReference && !groupingExprs.contains(projection)) {
                    finalProjectionsBuilder.add(new Alias(projection, projection.getName()));
                } else {
                    finalProjectionsBuilder.add(projection);
                }
            }
            return finalProjectionsBuilder.build();
        }
    }

    private List<Expression> bindGroupBy(
            Aggregate<Plan> agg, List<Expression> groupBy, List<NamedExpression> boundAggOutput,
            Supplier<Scope> aggOutputScopeWithoutAggFun, CascadesContext cascadesContext) {
        Scope childOutputScope = toScope(cascadesContext, agg.child().getOutput());

        SimpleExprAnalyzer analyzer = buildCustomSlotBinderAnalyzer(
                agg, cascadesContext, childOutputScope, true, true,
                (self, unboundSlot) -> {
                    // see: https://github.com/apache/doris/pull/15240
                    //
                    // first, try to bind by agg.child.output
                    List<Slot> slotsInChildren = self.bindExactSlotsByThisScope(unboundSlot, childOutputScope);
                    if (slotsInChildren.size() == 1) {
                        // bind succeed
                        return slotsInChildren;
                    }
                    // second, bind failed:
                    // if the slot not found, or more than one candidate slots found in agg.child.output,
                    // then try to bind by agg.output
                    List<Slot> slotsInOutput = self.bindExactSlotsByThisScope(
                            unboundSlot, aggOutputScopeWithoutAggFun.get());
                    if (slotsInOutput.isEmpty()) {
                        // if slotsInChildren.size() > 1 && slotsInOutput.isEmpty(),
                        // we return slotsInChildren to throw an ambiguous slots exception
                        return slotsInChildren;
                    }

                    Builder<Expression> useOutputExpr = ImmutableList.builderWithExpectedSize(slotsInOutput.size());
                    for (Slot slotInOutput : slotsInOutput) {
                        // mappingSlot is provided by aggOutputScopeWithoutAggFun
                        // and no non-MappingSlot slot exist in the Scope, so we
                        // can direct cast it safely
                        MappingSlot mappingSlot = (MappingSlot) slotInOutput;

                        // groupBy can not direct use the slot in agg.output, because
                        // groupBy evaluate before generate agg.output, so we should
                        // replace the output to the real expr tree
                        //
                        // for example:
                        //   select k + 1 as k1 from tbl group by k1
                        //
                        // The k1 in groupBy use the slot in agg.output: Alias(k + 1).toSlot(),
                        // we should rewrite to: select k + 1 as k1 from tbl group by k + 1
                        useOutputExpr.add(mappingSlot.getMappingExpression());
                    }
                    return useOutputExpr.build();
                });

        ImmutableList.Builder<Expression> boundGroupByBuilder = ImmutableList.builderWithExpectedSize(groupBy.size());
        for (Expression key : groupBy) {
            boundGroupByBuilder.add(bindWithOrdinal(key, analyzer, boundAggOutput));
        }
        List<Expression> boundGroupBy = boundGroupByBuilder.build();
        checkIfOutputAliasNameDuplicatedForGroupBy(boundGroupBy, boundAggOutput);
        return boundGroupBy;
    }

    private Supplier<Scope> buildAggOutputScopeWithoutAggFun(
            List<? extends NamedExpression> boundAggOutput, CascadesContext cascadesContext) {
        return Suppliers.memoize(() -> {
            Builder<Slot> nonAggFunOutput = ImmutableList.builderWithExpectedSize(boundAggOutput.size());
            for (NamedExpression output : boundAggOutput) {
                if (!output.containsType(AggregateFunction.class)) {
                    Slot outputSlot = output.toSlot();
                    Slot mappingSlot = new MappingSlot(outputSlot,
                            output instanceof Alias ? output.child(0) : output);
                    nonAggFunOutput.add(mappingSlot);
                }
            }
            return toScope(cascadesContext, nonAggFunOutput.build());
        });
    }

    private Plan bindSortWithoutSetOperation(MatchingContext<LogicalSort<Plan>> ctx) {
        CascadesContext cascadesContext = ctx.cascadesContext;
        LogicalSort<Plan> sort = ctx.root;
        Plan input = sort.child();
        List<Slot> childOutput = input.getOutput();

        // we should skip distinct project to bind slot in LogicalSort;
        // check input.child(0) to avoid process SELECT DISTINCT a FROM t ORDER BY b by mistake
        // NOTICE: SELECT a FROM (SELECT sum(a) AS a FROM t GROUP BY b) v ORDER BY b will not raise error result
        //   because input.child(0) is LogicalSubqueryAlias
        if (input instanceof LogicalProject && ((LogicalProject<?>) input).isDistinct()
                && (input.child(0) instanceof LogicalHaving
                || input.child(0) instanceof LogicalAggregate
                || input.child(0) instanceof LogicalRepeat)) {
            input = input.child(0);
        }
        // we should skip LogicalHaving to bind slot in LogicalSort;
        if (input instanceof LogicalHaving) {
            input = input.child(0);
        }

        // 1. We should deduplicate the slots, otherwise the binding process will fail due to the
        //    ambiguous slots exist.
        // 2. try to bound order-key with agg output, if failed, try to bound with output of agg.child
        //    binding priority example:
        //        select
        //        col1 * -1 as col1    # inner_col1 * -1 as alias_col1
        //        from
        //                (
        //                        select 1 as col1
        //                        union
        //                        select -2 as col1
        //                ) t
        //        group by col1
        //        order by col1;     # order by order_col1
        //    bind order_col1 with alias_col1, then, bind it with inner_col1
        List<Slot> inputSlots = input.getOutput();
        Scope inputScope = toScope(cascadesContext, inputSlots);

        final Plan finalInput = input;
        Supplier<Scope> inputChildrenScope = Suppliers.memoize(
                () -> toScope(cascadesContext, PlanUtils.fastGetChildrenOutputs(finalInput.children())));
        SimpleExprAnalyzer bindInInputScopeThenInputChildScope = buildCustomSlotBinderAnalyzer(
                sort, cascadesContext, inputScope, true, false,
                (self, unboundSlot) -> {
                    // first, try to bind slot in Scope(input.output)
                    List<Slot> slotsInInput = self.bindExactSlotsByThisScope(unboundSlot, inputScope);
                    if (!slotsInInput.isEmpty()) {
                        // bind succeed
                        return ImmutableList.of(slotsInInput.get(0));
                    }
                    // second, bind failed:
                    // if the slot not found, or more than one candidate slots found in input.output,
                    // then try to bind by input.children.output
                    return self.bindExactSlotsByThisScope(unboundSlot, inputChildrenScope.get());
                });

        SimpleExprAnalyzer bindInInputChildScope = getAnalyzerForOrderByAggFunc(finalInput, cascadesContext, sort,
                inputChildrenScope, inputScope);
        Builder<OrderKey> boundOrderKeys = ImmutableList.builderWithExpectedSize(sort.getOrderKeys().size());
        FunctionRegistry functionRegistry = cascadesContext.getConnectContext().getEnv().getFunctionRegistry();
        for (OrderKey orderKey : sort.getOrderKeys()) {
            Expression boundKey;
            if (hasAggregateFunction(orderKey.getExpr(), functionRegistry)) {
                boundKey = bindInInputChildScope.analyze(orderKey.getExpr());
            } else {
                boundKey = bindWithOrdinal(orderKey.getExpr(), bindInInputScopeThenInputChildScope, childOutput);
            }
            boundOrderKeys.add(orderKey.withExpression(boundKey));
        }
        return new LogicalSort<>(boundOrderKeys.build(), sort.child());
    }

    private LogicalTVFRelation bindTableValuedFunction(MatchingContext<UnboundTVFRelation> ctx) {
        UnboundTVFRelation unboundTVFRelation = ctx.root;
        StatementContext statementContext = ctx.statementContext;
        Env env = statementContext.getConnectContext().getEnv();
        FunctionRegistry functionRegistry = env.getFunctionRegistry();

        String functionName = unboundTVFRelation.getFunctionName();
        Properties arguments = unboundTVFRelation.getProperties();
        FunctionBuilder functionBuilder = functionRegistry.findFunctionBuilder(functionName, arguments);
        Pair<? extends Expression, ? extends BoundFunction> bindResult
                = functionBuilder.build(functionName, arguments);
        if (!(bindResult.first instanceof TableValuedFunction)) {
            throw new AnalysisException(bindResult.first.toSql() + " is not a TableValuedFunction");
        }
        Optional<SqlCacheContext> sqlCacheContext = statementContext.getSqlCacheContext();
        if (sqlCacheContext.isPresent()) {
            sqlCacheContext.get().setCannotProcessExpression(true);
        }
        return new LogicalTVFRelation(unboundTVFRelation.getRelationId(), (TableValuedFunction) bindResult.first);
    }

    private void checkIfOutputAliasNameDuplicatedForGroupBy(Collection<Expression> expressions,
            List<? extends NamedExpression> output) {
        // if group_by_and_having_use_alias_first=true, we should fall back to original planner until we
        // support the session variable.
        if (output.stream().noneMatch(Alias.class::isInstance)) {
            return;
        }
        List<Alias> aliasList = ExpressionUtils.filter(output, Alias.class);

        List<NamedExpression> exprAliasList =
                ExpressionUtils.collectAll(expressions, NamedExpression.class::isInstance);

        boolean isGroupByContainAlias = false;
        for (NamedExpression ne : exprAliasList) {
            for (Alias alias : aliasList) {
                if (!alias.getExprId().equals(ne.getExprId()) && alias.getName().equalsIgnoreCase(ne.getName())) {
                    isGroupByContainAlias = true;
                }
            }
            if (isGroupByContainAlias) {
                break;
            }
        }
    }

    private boolean isAggregateFunction(UnboundFunction unboundFunction, FunctionRegistry functionRegistry) {
        return functionRegistry.isAggregateFunction(
                    unboundFunction.getDbName(), unboundFunction.getName());
    }

    private Expression bindWithOrdinal(
            Expression unbound, SimpleExprAnalyzer analyzer, List<? extends Expression> boundSelectOutput) {
        if (unbound instanceof IntegerLikeLiteral) {
            int ordinal = ((IntegerLikeLiteral) unbound).getIntValue();
            if (ordinal >= 1 && ordinal <= boundSelectOutput.size()) {
                Expression boundSelectItem = boundSelectOutput.get(ordinal - 1);
                return boundSelectItem instanceof Alias ? boundSelectItem.child(0) : boundSelectItem;
            } else {
                return unbound; // bound literal
            }
        } else {
            return analyzer.analyze(unbound);
        }
    }

    private Scope toScope(CascadesContext cascadesContext, List<Slot> slots) {
        Optional<Scope> outerScope = cascadesContext.getOuterScope();
        if (outerScope.isPresent()) {
            return new Scope(outerScope, slots);
        } else {
            return new Scope(slots);
        }
    }

    private Scope toScope(CascadesContext cascadesContext, List<Slot> slots, List<Slot> asteriskSlots) {
        Optional<Scope> outerScope = cascadesContext.getOuterScope();
        if (outerScope.isPresent()) {
            return new Scope(outerScope, slots, asteriskSlots);
        } else {
            return new Scope(slots, asteriskSlots);
        }
    }

    protected SimpleExprAnalyzer buildSimpleExprAnalyzer(
            Plan currentPlan, CascadesContext cascadesContext, List<Plan> children) {
        Scope scope = toScope(cascadesContext, PlanUtils.fastGetChildrenOutputs(children),
                PlanUtils.fastGetChildrenAsteriskOutputs(children));
        ExpressionRewriteContext rewriteContext = new ExpressionRewriteContext(cascadesContext);
        ExpressionAnalyzer expressionAnalyzer = new ExpressionAnalyzer(currentPlan,
                scope, cascadesContext, true, true);
        return expr -> expressionAnalyzer.analyze(expr, rewriteContext);
    }

    private SimpleExprAnalyzer buildCustomSlotBinderAnalyzer(
            Plan currentPlan, CascadesContext cascadesContext, Scope defaultScope,
            boolean enableExactMatch, boolean bindSlotInOuterScope, CustomSlotBinderAnalyzer customSlotBinder) {
        ExpressionRewriteContext rewriteContext = new ExpressionRewriteContext(cascadesContext);
        ExpressionAnalyzer expressionAnalyzer = new ExpressionAnalyzer(currentPlan, defaultScope, cascadesContext,
                enableExactMatch, bindSlotInOuterScope) {
            @Override
            protected List<? extends Expression> bindSlotByThisScope(UnboundSlot unboundSlot) {
                return customSlotBinder.bindSlot(this, unboundSlot);
            }
        };
        return expr -> expressionAnalyzer.analyze(expr, rewriteContext);
    }

    /**SimpleExprAnalyzer*/
    protected interface SimpleExprAnalyzer {
        Expression analyze(Expression expr);

        default <E extends Expression> List<E> analyzeToList(List<E> exprs) {
            ImmutableList.Builder<E> result = ImmutableList.builderWithExpectedSize(exprs.size());
            for (E expr : exprs) {
                result.add((E) analyze(expr));
            }
            return result.build();
        }

        default <E extends Expression> Set<E> analyzeToSet(List<E> exprs) {
            ImmutableSet.Builder<E> result = ImmutableSet.builderWithExpectedSize(exprs.size() * 2);
            for (E expr : exprs) {
                result.add((E) analyze(expr));
            }
            return result.build();
        }
    }

    private interface CustomSlotBinderAnalyzer {
        List<? extends Expression> bindSlot(ExpressionAnalyzer analyzer, UnboundSlot unboundSlot);
    }

    public String toSqlWithBackquote(List<Slot> slots) {
        return slots.stream().map(slot -> ((SlotReference) slot).getQualifiedNameWithBackquote())
                .collect(Collectors.joining(", "));
    }

    private boolean hasAggregateFunction(Expression expression, FunctionRegistry functionRegistry) {
        return expression.anyMatch(expr -> {
            if (expr instanceof AggregateFunction) {
                return true;
            } else if (expr instanceof UnboundFunction) {
                UnboundFunction unboundFunction = (UnboundFunction) expr;
                boolean isAggregateFunction = functionRegistry
                        .isAggregateFunction(
                                unboundFunction.getDbName(),
                                unboundFunction.getName()
                        );
                return isAggregateFunction;
            }
            return false;
        });
    }

    private SimpleExprAnalyzer getAnalyzerForOrderByAggFunc(Plan finalInput, CascadesContext cascadesContext,
            LogicalSort<Plan> sort, Supplier<Scope> inputChildrenScope, Scope inputScope) {
        ImmutableList.Builder<Slot> outputSlots = ImmutableList.builder();
        if (finalInput instanceof LogicalAggregate) {
            LogicalAggregate<Plan> aggregate = (LogicalAggregate<Plan>) finalInput;
            List<NamedExpression> outputExpressions = aggregate.getOutputExpressions();
            for (NamedExpression outputExpr : outputExpressions) {
                if (!outputExpr.anyMatch(expr -> expr instanceof AggregateFunction)) {
                    outputSlots.add(outputExpr.toSlot());
                }
            }
        }
        Scope outputWithoutAggFunc = toScope(cascadesContext, outputSlots.build());
        SimpleExprAnalyzer bindInInputChildScope = buildCustomSlotBinderAnalyzer(
                sort, cascadesContext, inputScope, true, false,
                (analyzer, unboundSlot) -> {
                    if (finalInput instanceof LogicalAggregate) {
                        List<Slot> boundInOutputWithoutAggFunc = analyzer.bindSlotByScope(unboundSlot,
                                outputWithoutAggFunc);
                        if (!boundInOutputWithoutAggFunc.isEmpty()) {
                            return ImmutableList.of(boundInOutputWithoutAggFunc.get(0));
                        }
                    }
                    return analyzer.bindExactSlotsByThisScope(unboundSlot, inputChildrenScope.get());
                });
        return bindInInputChildScope;
    }
}
