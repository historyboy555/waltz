/*
 * Waltz - Enterprise Architecture
 * Copyright (C) 2016, 2017, 2018, 2019 Waltz open source project
 * See README.md for more information
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific
 *
 */

package org.finos.waltz.service.survey.inclusion_evaluator;

import org.apache.commons.jexl3.*;
import org.apache.commons.jexl3.introspection.JexlSandbox;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.survey.SurveyQuestion;
import org.finos.waltz.model.survey.SurveyQuestionResponse;
import org.jooq.DSLContext;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.finos.waltz.common.MapUtilities.newHashMap;
import static org.finos.waltz.common.StringUtilities.isEmpty;

public class QuestionPredicateEvaluator {

    /**
     * Methods callable from an inclusion predicate.  Anything not listed here (including
     * inherited members such as `getClass`) is rejected by the sandbox.
     */
    private static final String[] ALLOWED_NAMESPACE_METHODS = new String[]{
            "isChecked",
            "numberValue",
            "val",
            "ditto",
            "assessmentRating",
            "hasInvolvement",
            "isRetiring",
            "belongsToOrgUnit",
            "isAppKind",
            "hasLifecyclePhase",
            "hasDataType",
            "dataTypeUsages"
    };

    private static final String[] ALLOWED_STRING_METHODS = new String[]{
            "compareTo",
            "compareToIgnoreCase",
            "contains",
            "endsWith",
            "equals",
            "equalsIgnoreCase",
            "indexOf",
            "isEmpty",
            "length",
            "matches",
            "startsWith",
            "substring",
            "toLowerCase",
            "toString",
            "toUpperCase",
            "trim"
    };

    private static final String[] ALLOWED_VALUE_METHODS = new String[]{
            "booleanValue",
            "compareTo",
            "doubleValue",
            "equals",
            "intValue",
            "longValue",
            "toString"
    };

    private static final String[] ALLOWED_COLLECTION_METHODS = new String[]{
            "contains",
            "containsAll",
            "isEmpty",
            "size"
    };

    private static final String[] ALLOWED_MAP_METHODS = new String[]{
            "containsKey",
            "containsValue",
            "get",
            "isEmpty",
            "size"
    };


    public static List<SurveyQuestion> eval(DSLContext dsl,
                                     List<SurveyQuestion> qs,
                                     EntityReference subjectRef,
                                     Map<Long, SurveyQuestionResponse> responsesByQuestionId) {

        QuestionBasePredicateNamespace namespace = mkPredicateNameSpace(dsl, qs, subjectRef, responsesByQuestionId);

        JexlEngine jexl = mkEvaluationEngine(namespace);

        namespace.usingEvaluator(jexl);

        List<SurveyQuestion> activeQs = determineActiveQs(qs, jexl);

        return activeQs;
    }




    /**
     * Inclusion predicates are stored (and therefore modifiable via the survey template admin
     * screens) so they are evaluated on an engine which blocks everything by default and only
     * permits the namespace functions plus a handful of value/collection operations.
     */
    static JexlEngine mkEvaluationEngine(QuestionBasePredicateNamespace namespace) {
        JexlSandbox sandbox = new JexlSandbox(false, true);

        sandbox.allow(QuestionBasePredicateNamespace.class.getName())
                .execute(ALLOWED_NAMESPACE_METHODS);
        sandbox.allow(String.class.getName())
                .execute(ALLOWED_STRING_METHODS);
        sandbox.allow(Boolean.class.getName())
                .execute(ALLOWED_VALUE_METHODS);
        sandbox.allow(Character.class.getName())
                .execute(ALLOWED_VALUE_METHODS);
        sandbox.allow(Number.class.getName())
                .execute(ALLOWED_VALUE_METHODS);
        sandbox.allow(Collection.class.getName())
                .execute(ALLOWED_COLLECTION_METHODS);
        sandbox.allow(Set.class.getName())
                .execute(ALLOWED_COLLECTION_METHODS);
        sandbox.allow(List.class.getName())
                .execute(ALLOWED_COLLECTION_METHODS);
        sandbox.allow(Map.class.getName())
                .execute(ALLOWED_MAP_METHODS);

        return new JexlBuilder()
                .sandbox(sandbox)
                .namespaces(newHashMap(null, namespace))
                .create();
    }


    private static QuestionBasePredicateNamespace mkPredicateNameSpace(DSLContext dsl, List<SurveyQuestion> qs, EntityReference subjectRef, Map<Long, SurveyQuestionResponse> responsesByQuestionId) {
        switch (subjectRef.kind()) {
            case APPLICATION:
                return new QuestionAppPredicateNamespace(
                        dsl,
                        subjectRef,
                        qs,
                        responsesByQuestionId);
            case CHANGE_INITIATIVE:
                return new QuestionChangeInitiativePredicateNamespace(
                        dsl,
                        subjectRef,
                        qs,
                        responsesByQuestionId);
            default:
                return new QuestionBasePredicateNamespace(qs, responsesByQuestionId);
        }
    }


    private static List<SurveyQuestion> determineActiveQs(List<SurveyQuestion> qs, JexlEngine jexl) {
        List<SurveyQuestion> activeQs = qs
                .stream()
                .filter(q -> q
                        .inclusionPredicate()
                        .map(p -> {
                            if (isEmpty(p)) {
                                return true;
                            } else {
                                JexlExpression expr = jexl.createExpression(p);
                                JexlContext jexlCtx = new MapContext();
                                return Boolean.valueOf(expr.evaluate(jexlCtx).toString());
                            }
                        })
                        .orElse(true))
                .collect(Collectors.toList());
        return activeQs;
    }



}
