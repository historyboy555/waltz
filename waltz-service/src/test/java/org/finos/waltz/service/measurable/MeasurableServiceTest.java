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
import org.finos.waltz.model.changelog.ChangeLog;
import org.finos.waltz.model.measurable.Measurable;
import org.finos.waltz.service.changelog.ChangeLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.finos.waltz.model.EntityReference.mkRef;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MeasurableServiceTest {

    @Mock private MeasurableDao measurableDao;
    @Mock private MeasurableSearchDao measurableSearchDao;
    @Mock private EntityReferenceNameResolver nameResolver;
    @Mock private ChangeLogService changeLogService;

    private MeasurableService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new MeasurableService(measurableDao, measurableSearchDao, nameResolver, changeLogService);
    }

    @Test
    void emptyAndNullSearchQueriesReturnEmptyWithoutSearching() {
        assertTrue(service.search("").isEmpty());
        assertTrue(service.search((String) null).isEmpty());
        verifyNoInteractions(measurableSearchDao);
    }

    @Test
    void createWithIdOneWritesAuditButReturnsFalse() {
        Measurable measurable = mock(Measurable.class);
        when(measurable.name()).thenReturn("Measure");
        when(measurableDao.create(measurable)).thenReturn(1L);

        assertFalse(service.create(measurable, "actor"));

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService).write(captor.capture());
        assertEquals("created new measurable Measure", captor.getValue().message());
    }

    @Test
    void updatesUseUnknownNameAndNullExistingValueFallback() {
        when(measurableDao.getById(1L)).thenReturn(null);
        when(nameResolver.resolve(mkRef(EntityKind.MEASURABLE, 1L))).thenReturn(Optional.empty());
        when(measurableDao.updateName(1L, "new name", "actor")).thenReturn(false);

        assertFalse(service.updateName(1L, "new name", "actor"));

        ChangeLog log = capturedLog();
        assertEquals("Measurable: [UNKNOWN] name updated, from: [<null>] to: [new name]", log.message());
        // Audit is written before, and independently of, the DAO update result.
        verify(changeLogService).write(any(ChangeLog.class));
    }

    @Test
    void updatesLogDescriptionExternalIdAndConcreteFlagFormats() {
        Measurable current = mock(Measurable.class);
        when(current.description()).thenReturn("old description");
        when(current.externalId()).thenReturn(Optional.of("old-ext"));
        when(current.concrete()).thenReturn(false);
        when(measurableDao.getById(1L)).thenReturn(current);
        when(nameResolver.resolve(mkRef(EntityKind.MEASURABLE, 1L)))
                .thenReturn(Optional.of(namedRef(EntityKind.MEASURABLE, 1L, "Measure")));

        service.updateDescription(1L, "new description", "actor");
        service.updateExternalId(1L, "new-ext", "actor");
        service.updateConcreteFlag(1L, true, "actor");

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService, times(3)).write(captor.capture());
        List<String> messages = captor.getAllValues().stream().map(ChangeLog::message).toList();
        assertTrue(messages.contains("Measurable: [Measure] description updated, from: [old description] to: [new description]"));
        assertTrue(messages.contains("Measurable: [Measure] externalId updated, from: [old-ext] to: [new-ext]"));
        assertTrue(messages.contains("Measurable: [Measure] concrete flag updated, from: [false] to: [true]"));
    }

    @Test
    void reorderWithEmptyIdsDoesNothing() {
        assertTrue(service.reorder(10L, List.of(), "actor"));

        verifyNoInteractions(measurableDao, changeLogService);
    }

    @Test
    void updateParentIdUsesRootLabelForNullDestination() {
        when(nameResolver.resolve(mkRef(EntityKind.MEASURABLE, 1L)))
                .thenReturn(Optional.of(namedRef(EntityKind.MEASURABLE, 1L, "Measure")));
        when(measurableDao.updateParentId(1L, null, "actor")).thenReturn(true);

        assertTrue(service.updateParentId(1L, null, "actor"));

        assertEquals(
                "Measurable: [Measure] moved to new parent: [<root of tree>]",
                capturedLog().message());
        verify(measurableDao).updateParentId(1L, null, "actor");
    }

    private ChangeLog capturedLog() {
        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService).write(captor.capture());
        return captor.getValue();
    }

    private static EntityReference namedRef(EntityKind kind, long id, String name) {
        return org.finos.waltz.model.ImmutableEntityReference.builder()
                .kind(kind)
                .id(id)
                .name(name)
                .build();
    }
}
