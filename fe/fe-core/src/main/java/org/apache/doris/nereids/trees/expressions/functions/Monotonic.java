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

package org.apache.doris.nereids.trees.expressions.functions;

import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.literal.Literal;

/** monotonicity of expressions */
public interface Monotonic extends ExpressionTrait {
    default boolean isMonotonic(Literal lower, Literal upper) {
        return true;
    }

    // true means that the function is an increasing function
    boolean isPositive();

    // return the range input child index
    // e.g. date_trunc(dt,'xxx') return 0
    int getMonotonicFunctionChildIndex();

    // return the function with the arguments replaced by literal
    // e.g. date_trunc(dt, 'day'), dt in range ['2020-01-01 10:00:00', '2020-01-03 10:00:00']
    // return date_trunc('2020-01-01 10:00:00', 'day')
    Expression withConstantArgs(Expression literal);
}
