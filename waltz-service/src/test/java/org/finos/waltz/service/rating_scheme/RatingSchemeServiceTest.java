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

package org.finos.waltz.service.rating_scheme;

import org.finos.waltz.data.rating_scheme.RatingSchemeDAO;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.rating.ImmutableRatingScheme;
import org.finos.waltz.model.rating.ImmutableRatingSchemeItem;
import org.finos.waltz.model.rating.RatingScheme;
import org.finos.waltz.model.rating.RatingSchemeItem;
import org.jooq.Condition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static java.util.Collections.singleton;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests: these record the _current_ behaviour of
 * {@link RatingSchemeService}, which is a thin pass-through onto
 * {@link RatingSchemeDAO}.  The tests exist to pin down that no
 * filtering, defaulting or validation happens in the service layer.
 */
class RatingSchemeServiceTest {

    private final RatingSchemeDAO ratingSchemeDAO = mock(RatingSchemeDAO.class);
    private final RatingSchemeService service = new RatingSchemeService(ratingSchemeDAO);


    private static RatingScheme mkScheme(long id) {
        return ImmutableRatingScheme
                .builder()
                .id(id)
                .name("scheme-" + id)
                .description("d")
                .build();
    }


    private static RatingSchemeItem mkItem(long id, String code) {
        return ImmutableRatingSchemeItem
                .builder()
                .id(id)
                .ratingSchemeId(1L)
                .name("item-" + id)
                .rating(code)
                .color("red")
                .description("d")
                .position(1)
                .build();
    }


    @Test
    void findAllDelegatesToTheDao() {
        List<RatingScheme> schemes = singletonList(mkScheme(1L));
        when(ratingSchemeDAO.findAll()).thenReturn(schemes);

        assertSame(schemes, service.findAll());
    }


    @Test
    void getByIdReturnsWhateverTheDaoReturnsIncludingNull() {
        when(ratingSchemeDAO.getById(1L)).thenReturn(mkScheme(1L));
        when(ratingSchemeDAO.getById(2L)).thenReturn(null);

        assertEquals(mkScheme(1L), service.getById(1L));
        assertNull(service.getById(2L), "no 'not found' exception is thrown");
    }


    @Test
    void findAllRatingSchemeItemsFetchesItemsWithAnUnrestrictedCondition() {
        when(ratingSchemeDAO.fetchItems(any(Condition.class))).thenReturn(emptyList());

        service.findAllRatingSchemeItems();

        ArgumentCaptor<Condition> captor = ArgumentCaptor.forClass(Condition.class);
        verify(ratingSchemeDAO).fetchItems(captor.capture());
        assertEquals("true", captor.getValue().toString(), "an always-true condition is used");
    }


    @Test
    void itemLookupsAreDirectDelegations() {
        List<RatingSchemeItem> items = singletonList(mkItem(1L, "A"));
        Set<RatingSchemeItem> itemSet = singleton(mkItem(2L, "B"));
        EntityReference ref = mkRef(EntityKind.APPLICATION, 55L);

        when(ratingSchemeDAO.findRatingSchemeItemsForAssessmentDefinition(10L)).thenReturn(items);
        when(ratingSchemeDAO.findRatingSchemeItemsForEntityAndCategory(ref, 20L)).thenReturn(items);
        when(ratingSchemeDAO.findRatingSchemeItemsByIds(Collections.singleton(3L))).thenReturn(itemSet);
        when(ratingSchemeDAO.findRatingSchemeItemsForSchemeIds(Collections.singleton(4L))).thenReturn(itemSet);
        // note: the dao has both a long and a Long overload of this method; the
        // service binds to the Long one, so the stub must be boxed explicitly
        Long boxedId = 5L;
        when(ratingSchemeDAO.getRatingSchemeItemById(boxedId)).thenReturn(mkItem(5L, "C"));

        assertSame(items, service.findRatingSchemeItemsByAssessmentDefinition(10L));
        assertSame(items, service.findRatingSchemeItemsForEntityAndCategory(ref, 20L));
        assertSame(itemSet, service.findRatingSchemeItemsByIds(Collections.singleton(3L)));
        assertSame(itemSet, service.findRatingSchemeItemsBySchemeIds(Collections.singleton(4L)));
        assertEquals(mkItem(5L, "C"), service.getRatingSchemeItemById(5L));
    }


    @Test
    void emptyIdSetsAreStillPassedToTheDaoRatherThanShortCircuited() {
        when(ratingSchemeDAO.findRatingSchemeItemsByIds(emptySet())).thenReturn(emptySet());
        when(ratingSchemeDAO.findRatingSchemeItemsForSchemeIds(emptySet())).thenReturn(emptySet());

        assertTrue(service.findRatingSchemeItemsByIds(emptySet()).isEmpty());
        assertTrue(service.findRatingSchemeItemsBySchemeIds(emptySet()).isEmpty());

        verify(ratingSchemeDAO).findRatingSchemeItemsByIds(emptySet());
        verify(ratingSchemeDAO).findRatingSchemeItemsForSchemeIds(emptySet());
    }


    @Test
    void mutationsAndStatsAreDirectDelegations() {
        RatingScheme scheme = mkScheme(1L);
        RatingSchemeItem item = mkItem(1L, "A");

        when(ratingSchemeDAO.save(scheme)).thenReturn(true);
        when(ratingSchemeDAO.saveRatingItem(7L, item)).thenReturn(99L);
        when(ratingSchemeDAO.removeRatingItem(8L)).thenReturn(true);
        when(ratingSchemeDAO.removeRatingScheme(9L)).thenReturn(false);
        when(ratingSchemeDAO.calcRatingUsageStats()).thenReturn(emptyList());

        assertEquals(true, service.save(scheme));
        assertEquals(99L, service.saveRatingItem(7L, item));
        assertEquals(true, service.removeRatingItem(8L));
        assertEquals(false, service.removeRatingScheme(9L));
        assertTrue(service.calcRatingUsageStats().isEmpty());

        verify(ratingSchemeDAO).save(scheme);
        verify(ratingSchemeDAO).saveRatingItem(7L, item);
        verify(ratingSchemeDAO).removeRatingItem(8L);
        verify(ratingSchemeDAO).removeRatingScheme(9L);
    }
}
