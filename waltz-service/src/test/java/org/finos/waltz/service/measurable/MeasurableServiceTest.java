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

package org.finos.waltz.service.measurable;

import org.finos.waltz.data.EntityReferenceNameResolver;
import org.finos.waltz.data.measurable.MeasurableDao;
import org.finos.waltz.data.measurable.search.MeasurableSearchDao;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.Operation;
import org.finos.waltz.model.Severity;
import org.finos.waltz.model.changelog.ChangeLog;
import org.finos.waltz.model.entity_search.EntitySearchOptions;
import org.finos.waltz.model.measurable.ImmutableMeasurable;
import org.finos.waltz.model.measurable.Measurable;
import org.finos.waltz.service.changelog.ChangeLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link MeasurableService}.  The service is mostly
 * a facade over {@link MeasurableDao} but adds search short-circuiting, audit
 * logging and a couple of surprising return values (flagged inline).
 */
class MeasurableServiceTest {

    private final MeasurableDao measurableDao = mock(MeasurableDao.class);
    private final MeasurableSearchDao measurableSearchDao = mock(MeasurableSearchDao.class);
    private final EntityReferenceNameResolver nameResolver = mock(EntityReferenceNameResolver.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);

    private final MeasurableService service = new MeasurableService(
            measurableDao,
            measurableSearchDao,
            nameResolver,
            changeLogService);


    private static Measurable mkMeasurable(long id, String name) {
        return ImmutableMeasurable
                .builder()
                .id(id)
                .parentId(Optional.empty())
                .name(name)
                .description("desc of " + name)
                .categoryId(1L)
                .concrete(true)
                .organisationalUnitId(20L)
                .lastUpdatedBy("tester")
                .lastUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0))
                .provenance("waltz")
                .build();
    }


    private ChangeLog captureAuditEntry() {
        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService).write(captor.capture());
        return captor.getValue();
    }


    @Test
    void constructionRejectsNullCollaborators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeasurableService(null, measurableSearchDao, nameResolver, changeLogService));
    }


    @Test
    void searchWithAnEmptyQueryDoesNotHitTheSearchDao() {
        assertEquals(emptyList(), service.search(""));
        assertEquals(emptyList(), service.search((String) null));
        assertEquals(emptyList(), service.search("   "), "whitespace only queries are treated as empty");

        verifyNoInteractions(measurableSearchDao);
    }


    @Test
    void searchWithAQueryBuildsDefaultMeasurableSearchOptions() {
        when(measurableSearchDao.search(any(EntitySearchOptions.class))).thenReturn(emptyList());

        service.search("abc");

        ArgumentCaptor<EntitySearchOptions> captor = ArgumentCaptor.forClass(EntitySearchOptions.class);
        verify(measurableSearchDao).search(captor.capture());

        assertEquals("abc", captor.getValue().searchQuery());
        assertEquals(singletonList(EntityKind.MEASURABLE), captor.getValue().entityKinds());
    }


    @Test
    void simpleLookupsAreDelegatedToTheDao() {
        List<Measurable> measurables = singletonList(mkMeasurable(1L, "m1"));

        when(measurableDao.findAll()).thenReturn(measurables);
        when(measurableDao.findByCategoryId(1L)).thenReturn(measurables);
        when(measurableDao.findByParentId(2L)).thenReturn(measurables);
        when(measurableDao.findByExternalId("ext")).thenReturn(measurables);
        when(measurableDao.findByOrgUnitId(3L)).thenReturn(measurables);
        when(measurableDao.getById(1L)).thenReturn(mkMeasurable(1L, "m1"));

        assertSame(measurables, service.findAll());
        assertSame(measurables, service.findByCategoryId(1L));
        assertSame(measurables, service.findByParentId(2L));
        assertSame(measurables, service.findByExternalId("ext"));
        assertSame(measurables, service.findByOrgUnitId(3L));
        assertEquals(mkMeasurable(1L, "m1"), service.getById(1L));
    }


    @Test
    void selectorBasedLookupsRejectNullOptions() {
        assertThrows(IllegalArgumentException.class, () -> service.findByMeasurableIdSelector(null));
        assertThrows(IllegalArgumentException.class, () -> service.findByRatingIdSelector(null));
        assertThrows(IllegalArgumentException.class, () -> service.findExternalIdToIdMapByCategoryId(null));
    }


    @Test
    void updateNameAuditsThePreviousAndNewValueBeforeUpdating() {
        when(measurableDao.getById(1L)).thenReturn(mkMeasurable(1L, "old name"));
        when(nameResolver.resolve(mkRef(EntityKind.MEASURABLE, 1L)))
                .thenReturn(Optional.of(mkRef(EntityKind.MEASURABLE, 1L, "old name")));
        when(measurableDao.updateName(1L, "new name", "tester")).thenReturn(true);

        assertTrue(service.updateName(1L, "new name", "tester"));

        ChangeLog entry = captureAuditEntry();
        assertEquals(
                "Measurable: [old name] name updated, from: [old name] to: [new name]",
                entry.message());
        assertEquals(mkRef(EntityKind.MEASURABLE, 1L), entry.parentReference());
        assertEquals(Severity.INFORMATION, entry.severity());
        assertEquals(Operation.UPDATE, entry.operation());
    }


    @Test
    void updatesAreAuditedEvenWhenTheMeasurableDoesNotExistOrTheUpdateFails() {
        // SUSPECTED BUG (characterized, not fixed): the audit entry is written
        // *before* the dao update and is not rolled back / suppressed when the
        // update affects no rows, so the change log can contain updates which
        // never happened.  Unknown measurables are logged as `<null>` -> value.
        when(measurableDao.getById(99L)).thenReturn(null);
        when(nameResolver.resolve(any(EntityReference.class))).thenReturn(Optional.empty());
        when(measurableDao.updateDescription(99L, "d", "tester")).thenReturn(false);

        assertFalse(service.updateDescription(99L, "d", "tester"));

        assertEquals(
                "Measurable: [UNKNOWN] description updated, from: [<null>] to: [d]",
                captureAuditEntry().message());
    }


    @Test
    void createReportsSuccessOnlyWhenTheNewIdIsGreaterThanOne() {
        Measurable m = mkMeasurable(1L, "new measurable");

        when(measurableDao.create(m)).thenReturn(2L);
        assertTrue(service.create(m, "tester"));

        // SUSPECTED BUG (characterized, not fixed): `create` returns
        // `measurableId > 1`, so a successful insert which happens to be
        // allocated id 1 (or 0) is reported to the caller as a failure.
        when(measurableDao.create(m)).thenReturn(1L);
        assertFalse(service.create(m, "tester"));
    }


    @Test
    void updateParentIdDescribesAMoveToTheRootOfTheTree() {
        when(nameResolver.resolve(mkRef(EntityKind.MEASURABLE, 1L)))
                .thenReturn(Optional.of(mkRef(EntityKind.MEASURABLE, 1L, "child")));
        when(measurableDao.updateParentId(1L, null, "tester")).thenReturn(true);

        assertTrue(service.updateParentId(1L, null, "tester"));

        assertEquals(
                "Measurable: [child] moved to new parent: [<root of tree>]",
                captureAuditEntry().message());
    }


    @Test
    void updateParentIdRejectsANullMeasurableId() {
        assertThrows(IllegalArgumentException.class, () -> service.updateParentId(null, 2L, "tester"));
        verifyNoInteractions(measurableDao, changeLogService);
    }


    @Test
    void reorderWithNoIdsClaimsSuccessWithoutDoingAnything() {
        assertTrue(service.reorder(1L, emptyList(), "tester"));
        assertTrue(service.reorder(1L, null, "tester"));

        verify(measurableDao, never()).reorder(anyLong(), anyList(), anyString());
        verifyNoInteractions(changeLogService);
    }


    @Test
    void reorderAlwaysReturnsTrueAndAuditsAgainstTheCategory() {
        assertTrue(service.reorder(7L, asList(3L, 2L, 1L), "tester"));

        verify(measurableDao).reorder(7L, asList(3L, 2L, 1L), "tester");

        ChangeLog entry = captureAuditEntry();
        assertEquals("Reordered 3 measurables", entry.message());
        assertEquals(
                mkRef(EntityKind.MEASURABLE_CATEGORY, 7L),
                entry.parentReference(),
                "reorder is audited against the category, not the measurables");
    }


    @Test
    void hierarchyAndChildMovesAreDirectDelegations() {
        when(measurableDao.moveChildren(1L, 2L, "tester")).thenReturn(true);
        when(measurableDao.findHierarchyForCategory(1L)).thenReturn(emptySet());

        assertTrue(service.moveChildren(1L, 2L, "tester"));
        assertTrue(service.findHierarchyForCategory(1L).isEmpty());
        verifyNoInteractions(changeLogService);
    }
}
