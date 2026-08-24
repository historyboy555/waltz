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

package org.finos.waltz.service.changelog;

import org.finos.waltz.data.EntityReferenceNameResolver;
import org.finos.waltz.data.application.ApplicationDao;
import org.finos.waltz.data.changelog.ChangeLogDao;
import org.finos.waltz.data.changelog.ChangeLogSummariesDao;
import org.finos.waltz.data.logical_flow.LogicalFlowDao;
import org.finos.waltz.data.measurable_rating.MeasurableRatingDao;
import org.finos.waltz.data.measurable_rating_planned_decommission.MeasurableRatingPlannedDecommissionDao;
import org.finos.waltz.data.measurable_rating_replacement.MeasurableRatingReplacementDao;
import org.finos.waltz.data.physical_flow.PhysicalFlowDao;
import org.finos.waltz.data.physical_specification.PhysicalSpecificationDao;
import org.finos.waltz.model.*;
import org.finos.waltz.model.changelog.ChangeLog;
import org.finos.waltz.model.logical_flow.LogicalFlow;
import org.finos.waltz.model.measurable_rating.MeasurableRating;
import org.finos.waltz.model.measurable_rating_planned_decommission.MeasurableRatingPlannedDecommission;
import org.finos.waltz.model.measurable_rating_replacement.MeasurableRatingReplacement;
import org.finos.waltz.model.physical_flow.PhysicalFlow;
import org.finos.waltz.model.physical_specification.PhysicalSpecification;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.finos.waltz.model.EntityKind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChangeLogServiceTest {

    @Mock private ChangeLogDao changeLogDao;
    @Mock private ChangeLogSummariesDao changeLogSummariesDao;
    @Mock private PhysicalFlowDao physicalFlowDao;
    @Mock private PhysicalSpecificationDao physicalSpecificationDao;
    @Mock private LogicalFlowDao logicalFlowDao;
    @Mock private ApplicationDao applicationDao;
    @Mock private MeasurableRatingReplacementDao replacementDao;
    @Mock private MeasurableRatingDao measurableRatingDao;
    @Mock private MeasurableRatingPlannedDecommissionDao plannedDecommissionDao;
    @Mock private EntityReferenceNameResolver nameResolver;

    private ChangeLogService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new ChangeLogService(
                changeLogDao,
                changeLogSummariesDao,
                physicalFlowDao,
                physicalSpecificationDao,
                logicalFlowDao,
                applicationDao,
                replacementDao,
                measurableRatingDao,
                plannedDecommissionDao,
                nameResolver);
    }

    @Test
    void writeOverloadsDelegateToDao() {
        ChangeLog changeLog = mock(ChangeLog.class);
        DSLContext tx = mock(DSLContext.class);
        when(changeLogDao.write(any(Optional.class), same(changeLog))).thenReturn(7);
        when(changeLogDao.write(anyCollection())).thenReturn(new int[]{1, 2});

        assertEquals(7, service.write(changeLog));
        assertEquals(7, service.write(Optional.of(tx), changeLog));
        assertArrayEquals(new int[]{1, 2}, service.write(List.of(changeLog)));
        verify(changeLogDao).write(Optional.empty(), changeLog);
        verify(changeLogDao).write(Optional.of(tx), changeLog);
        verify(changeLogDao).write(List.of(changeLog));
    }

    @Test
    void findByUserRejectsEmptyUsername() {
        assertThrows(IllegalArgumentException.class, () -> service.findByUser("", Optional.empty()));
        verifyNoInteractions(changeLogDao);
    }

    @Test
    void referenceQueriesRejectNullReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> service.findByParentReference(null, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> service.findByPersonReference(null, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> service.findByParentReferenceForDateRange(null, null, null, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> service.findByPersonReferenceForDateRange(null, null, null, Optional.empty()));
    }

    @Test
    void logicalFlowDispatchComposesMessageAndFansOutToFlowAndEndpoints() {
        EntityReference flowRef = ref(LOGICAL_DATA_FLOW, 10, null);
        EntityReference source = ref(APPLICATION, 1, "Source");
        EntityReference target = ref(APPLICATION, 2, "Target");
        LogicalFlow flow = mock(LogicalFlow.class);
        when(flow.entityReference()).thenReturn(flowRef);
        when(flow.source()).thenReturn(source);
        when(flow.target()).thenReturn(target);
        when(logicalFlowDao.getByFlowId(10L)).thenReturn(flow);

        service.writeChangeLogEntries(flowRef, "actor", "changed", Operation.UPDATE);

        Set<ChangeLog> entries = capturedEntries();
        assertEquals(Set.of(flowRef, source, target), parents(entries));
        assertAll(entries.stream().map(entry -> () -> {
            assertEquals("Logical flow from: Source [1], to: Target [2]: changed", entry.message());
            assertEquals(Optional.of(LOGICAL_DATA_FLOW), entry.childKind());
            assertEquals(Optional.of(10L), entry.childId());
            assertEquals("actor", entry.userId());
        }));
    }

    @Test
    void physicalFlowDispatchComposesMessageAndFansOutToPhysicalLogicalAndEndpoints() {
        EntityReference physicalRef = ref(PHYSICAL_FLOW, 30, null);
        EntityReference logicalRef = ref(LOGICAL_DATA_FLOW, 10, null);
        EntityReference source = ref(APPLICATION, 1, "Source");
        EntityReference target = ref(APPLICATION, 2, "Target");
        PhysicalFlow physical = mock(PhysicalFlow.class);
        LogicalFlow logical = mock(LogicalFlow.class);
        PhysicalSpecification specification = mock(PhysicalSpecification.class);
        when(physical.entityReference()).thenReturn(physicalRef);
        when(physical.logicalFlowId()).thenReturn(10L);
        when(physical.specificationId()).thenReturn(20L);
        when(logical.entityReference()).thenReturn(logicalRef);
        when(logical.source()).thenReturn(source);
        when(logical.target()).thenReturn(target);
        when(specification.name()).thenReturn("Specification");
        when(logicalFlowDao.getByFlowId(10L)).thenReturn(logical);
        when(physicalSpecificationDao.getById(20L)).thenReturn(specification);
        when(physicalFlowDao.getById(30L)).thenReturn(physical);

        service.writeChangeLogEntries(physicalRef, "actor", "changed", Operation.UPDATE);

        Set<ChangeLog> entries = capturedEntries();
        assertEquals(Set.of(physicalRef, logicalRef, source, target), parents(entries));
        assertEquals("Physical flow: Specification, from: Source [1], to: Target [2]: changed",
                entries.iterator().next().message());
        assertEquals(Optional.of(PHYSICAL_FLOW), entries.iterator().next().childKind());
        assertEquals(Optional.of(30L), entries.iterator().next().childId());
    }

    @Test
    void physicalSpecificationDispatchIncludesSpecificationAndItsFlows() {
        EntityReference specRef = ref(PHYSICAL_SPECIFICATION, 20, null);
        EntityReference flowRef = ref(PHYSICAL_FLOW, 30, null);
        PhysicalSpecification specification = mock(PhysicalSpecification.class);
        PhysicalFlow flow = mock(PhysicalFlow.class);
        when(specification.id()).thenReturn(Optional.of(20L));
        when(specification.entityReference()).thenReturn(specRef);
        when(specification.name()).thenReturn("Specification");
        when(flow.entityReference()).thenReturn(flowRef);
        when(physicalSpecificationDao.getById(20L)).thenReturn(specification);
        when(physicalFlowDao.findBySpecificationId(20L)).thenReturn(List.of(flow));

        service.writeChangeLogEntries(specRef, "actor", "changed", Operation.UPDATE);

        Set<ChangeLog> entries = capturedEntries();
        assertEquals(Set.of(specRef, flowRef), parents(entries));
        assertEquals("Physical spec: Specification: changed", entries.iterator().next().message());
        assertEquals(Optional.of(PHYSICAL_SPECIFICATION), entries.iterator().next().childKind());
        assertEquals(Optional.of(20L), entries.iterator().next().childId());
    }

    @Test
    void ratingReplacementAndPlannedDecommissionDispatchPopulateChildKindAndId() {
        EntityReference replacementRef = ref(MEASURABLE_RATING_REPLACEMENT, 200, "Replacement");
        EntityReference originalRef = ref(MEASURABLE, 55, "Original");
        MeasurableRatingReplacement replacement = mock(MeasurableRatingReplacement.class);
        MeasurableRatingPlannedDecommission decommission = mock(MeasurableRatingPlannedDecommission.class);
        MeasurableRating rating = mock(MeasurableRating.class);
        when(replacement.entityReference()).thenReturn(replacementRef);
        when(replacement.decommissionId()).thenReturn(300L);
        when(decommission.measurableRatingId()).thenReturn(400L);
        when(decommission.id()).thenReturn(300L);
        when(rating.measurableId()).thenReturn(55L);
        when(rating.entityReference()).thenReturn(originalRef);
        when(replacementDao.getById(200L)).thenReturn(replacement);
        when(plannedDecommissionDao.getById(300L)).thenReturn(decommission);
        when(measurableRatingDao.getById(400L)).thenReturn(rating);
        when(nameResolver.resolve(ref(MEASURABLE, 55, null)))
                .thenReturn(Optional.of(ref(MEASURABLE, 55, "Measurable")));
        when(nameResolver.resolve(ref(MEASURABLE_RATING_REPLACEMENT, 200, null)))
                .thenReturn(Optional.of(ref(MEASURABLE_RATING_REPLACEMENT, 200, "Replacement")));

        service.writeChangeLogEntries(replacementRef, "actor", "changed", Operation.UPDATE);
        Set<ChangeLog> replacementEntries = capturedEntries();
        assertEquals(Set.of(replacementRef, originalRef), parents(replacementEntries));
        ChangeLog replacementEntry = replacementEntries.iterator().next();
        assertEquals("Replacement measurable_rating_replacement: Replacement [200], for measurable: Measurable [55] on: Measurable [55]: changed",
                replacementEntry.message());
        assertEquals(Optional.of(MEASURABLE_RATING_REPLACEMENT), replacementEntry.childKind());
        // These overloads currently populate only childKind; pin the absent child id as-is.
        assertEquals(Optional.empty(), replacementEntry.childId());

        clearInvocations(changeLogDao);
        when(replacementDao.fetchByDecommissionId(300L)).thenReturn(Set.of(replacement));
        service.writeChangeLogEntries(
                ref(MEASURABLE_RATING_PLANNED_DECOMMISSION, 300, null),
                "actor",
                "changed",
                Operation.UPDATE);
        Set<ChangeLog> plannedEntries = capturedEntries();
        assertEquals(Set.of(replacementRef, originalRef), parents(plannedEntries));
        ChangeLog plannedEntry = plannedEntries.iterator().next();
        assertEquals("Measurable Rating: Measurable [55] on: Measurable [55]: changed", plannedEntry.message());
        assertEquals(Optional.of(MEASURABLE_RATING_PLANNED_DECOMMISSION), plannedEntry.childKind());
        // These overloads currently populate only childKind; pin the absent child id as-is.
        assertEquals(Optional.empty(), plannedEntry.childId());
    }

    @Test
    void unhandledKindDoesNothing() {
        EntityReference ref = ref(APPLICATION, 1, null);

        service.writeChangeLogEntries(ref, "actor", "changed", Operation.UPDATE);

        verifyNoInteractions(
                physicalFlowDao,
                physicalSpecificationDao,
                logicalFlowDao,
                replacementDao,
                plannedDecommissionDao,
                changeLogDao);
    }

    private Set<ChangeLog> capturedEntries() {
        ArgumentCaptor<Set> captor = ArgumentCaptor.forClass(Set.class);
        verify(changeLogDao).write(captor.capture());
        return (Set<ChangeLog>) captor.getValue();
    }

    private static Set<EntityReference> parents(Set<ChangeLog> entries) {
        return entries.stream().map(ChangeLog::parentReference).collect(java.util.stream.Collectors.toSet());
    }

    private static EntityReference ref(EntityKind kind, long id, String name) {
        ImmutableEntityReference.Builder builder = ImmutableEntityReference.builder()
                .kind(kind)
                .id(id);
        if (name != null) {
            builder.name(name);
        }
        return builder.build();
    }
}
