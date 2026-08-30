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

import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.MapContext;
import org.finos.waltz.model.survey.ImmutableSurveyQuestion;
import org.finos.waltz.model.survey.ImmutableSurveyQuestionResponse;
import org.finos.waltz.model.survey.SurveyQuestion;
import org.finos.waltz.model.survey.SurveyQuestionFieldType;
import org.finos.waltz.model.survey.SurveyQuestionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class QuestionPredicateEvaluatorTest {

    private static final SurveyQuestion Q1 = ImmutableSurveyQuestion
            .builder()
            .id(1L)
            .externalId("Q1")
            .surveyTemplateId(1L)
            .questionText("Is it checked?")
            .fieldType(SurveyQuestionFieldType.BOOLEAN)
            .build();

    private static final SurveyQuestionResponse Q1_RESPONSE = ImmutableSurveyQuestionResponse
            .builder()
            .questionId(1L)
            .booleanResponse(true)
            .build();


    @Test
    public void namespaceFunctionsAreEvaluable() {
        assertEquals(
                Boolean.TRUE,
                evaluate("isChecked('Q1')"));
        assertEquals(
                Boolean.FALSE,
                evaluate("isChecked('Q1') && numberValue('Q1', 1.0) > 2"));
    }


    @Test
    public void allowedValueMethodsAreEvaluable() {
        assertEquals(
                "ABC",
                evaluate("'abc'.toUpperCase()"));
    }


    @Test
    public void reflectiveAccessIsBlocked() {
        assertNull(evaluate("''.getClass()"));
        assertNull(evaluate("''.getClass().getName()"));
        assertNull(evaluate("''.getClass().forName('java.lang.Runtime')"));
        assertNull(evaluate("''.getClass().forName('java.lang.Runtime').getRuntime().exec('id')"));
    }


    @Test
    public void constructionIsBlocked() {
        assertThrows(
                JexlException.class,
                () -> evaluate("new('java.lang.ProcessBuilder', 'id')"));
    }


    @Test
    public void namespaceMethodsOutsideTheAllowListAreBlocked() {
        assertThrows(
                JexlException.class,
                () -> evaluate("usingEvaluator(null)"));
    }


    private Object evaluate(String predicate) {
        List<SurveyQuestion> qs = singletonList(Q1);
        Map<Long, SurveyQuestionResponse> responses = singletonMap(1L, Q1_RESPONSE);

        QuestionBasePredicateNamespace namespace = new QuestionBasePredicateNamespace(qs, responses);
        JexlEngine jexl = QuestionPredicateEvaluator.mkEvaluationEngine(namespace);
        namespace.usingEvaluator(jexl);

        JexlContext ctx = new MapContext();
        return jexl.createExpression(predicate).evaluate(ctx);
    }

}
