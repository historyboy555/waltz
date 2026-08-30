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

package org.finos.waltz.service.logical_flow;

import org.finos.waltz.data.DBExecutorPoolInterface;
import org.finos.waltz.data.datatype_decorator.LogicalFlowDecoratorDao;
import org.finos.waltz.data.datatype_decorator.PhysicalSpecDecoratorDao;
import org.finos.waltz.data.logical_flow.LogicalFlowDao;
import org.finos.waltz.data.logical_flow.LogicalFlowStatsDao;
import org.finos.waltz.data.physical_flow.PhysicalFlowDao;
import org.finos.waltz.data.physical_specification.PhysicalSpecificationDao;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.IdSelectionOptions;
import org.finos.waltz.model.Operation;
import org.finos.waltz.model.changelog.ChangeLog;
import org.finos.waltz.model.datatype.DataType;
import org.finos.waltz.model.datatype.DataTypeDecorator;
import org.finos.waltz.model.datatype.ImmutableDataType;
import org.finos.waltz.model.logical_flow.ImmutableAddLogicalFlowCommand;
import org.finos.waltz.model.logical_flow.ImmutableLogicalFlow;
import org.finos.waltz.model.logical_flow.LogicalFlow;
import org.finos.waltz.service.assessment_definition.AssessmentDefinitionService;
import org.finos.waltz.service.assessment_rating.AssessmentRatingService;
import org.finos.waltz.service.changelog.ChangeLogService;
import org.finos.waltz.service.data_type.DataTypeService;
import org.finos.waltz.service.permission.permission_checker.FlowPermissionChecker;
import org.finos.waltz.service.rating_scheme.RatingSchemeService;
import org.finos.waltz.service.usage_info.DataTypeUsageService;
import org.jooq.Select;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.finos.waltz.common.SetUtilities.asSet;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link LogicalFlowService}.  Covers the guard
 * rails around flow creation / removal, the read-only toggle and the stats
 * dispatch.  Two behaviours which look wrong are pinned here and reported in
 * the PR rather than being fixed.
 */
class LogicalFlowServiceTest {

    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final DataTypeService dataTypeService = mock(DataTypeService.class);
    private final DataTypeUsageService dataTypeUsageService = mock(DataTypeUsageService.class);
    private final DBExecutorPoolInterface dbExecutorPool = mock(DBExecutorPoolInterface.class);
    private final LogicalFlowDao logicalFlowDao = mock(LogicalFlowDao.class);
    private final LogicalFlowStatsDao logicalFlowStatsDao = mock(LogicalFlowStatsDao.class);
    private final LogicalFlowDecoratorDao logicalFlowDecoratorDao = mock(LogicalFlowDecoratorDao.class);
    private final FlowPermissionChecker flowPermissionChecker = mock(FlowPermissionChecker.class);
    private final PhysicalSpecDecoratorDao physicalSpecDecoratorDao = mock(PhysicalSpecDecoratorDao.class);
    private final AssessmentRatingService assessmentRatingService = mock(AssessmentRatingService.class);
    private final AssessmentDefinitionService assessmentDefinitionService = mock(AssessmentDefinitionService.class);
    private final PhysicalFlowDao physicalFlowDao = mock(PhysicalFlowDao.class);
    private final PhysicalSpecificationDao physicalSpecificationDao = mock(PhysicalSpecificationDao.class);
    private final RatingSchemeService ratingSchemeService = mock(RatingSchemeService.class);

    private final LogicalFlowService service = mkService();

    private final EntityReference source = mkRef(EntityKind.APPLICATION, 1L, "App A");
    private final EntityReference target = mkRef(EntityKind.APPLICATION, 2L, "App B");


    private LogicalFlowService mkService() {
        return new LogicalFlowService(
                changeLogService,
                dataTypeService,
                dataTypeUsageService,
                dbExecutorPool,
                logicalFlowDao,
                logicalFlowStatsDao,
                logicalFlowDecoratorDao,
                flowPermissionChecker,
                physicalSpecDecoratorDao,
                assessmentRatingService,
                assessmentDefinitionService,
                physicalFlowDao,
                physicalSpecificationDao,
                ratingSchemeService);
    }


    private LogicalFlow mkFlow(long id, boolean isReadOnly) {
        return ImmutableLogicalFlow
                .builder()
                .id(id)
                .source(source)
                .target(target)
                .isReadOnly(isReadOnly)
                .lastUpdatedBy("tester")
                .lastUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0))
                .build();
    }


    @Test
    void constructionRejectsNullCollaborators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LogicalFlowService(
                        null,
                        dataTypeService,
                        dataTypeUsageService,
                        dbExecutorPool,
                        logicalFlowDao,
                        logicalFlowStatsDao,
                        logicalFlowDecoratorDao,
                        flowPermissionChecker,
                        physicalSpecDecoratorDao,
                        assessmentRatingService,
                        assessmentDefinitionService,
                        physicalFlowDao,
                        physicalSpecificationDao,
                        ratingSchemeService));
    }


    @Test
    void addFlowRejectsSelfLoops() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.addFlow(
                        ImmutableAddLogicalFlowCommand
                                .builder()
                                .source(source)
                                .target(source)
                                .build(),
                        "tester"));

        assertEquals("Cannot have a flow with same source and target", ex.getMessage());
        verifyNoInteractions(logicalFlowDao);
    }


    @Test
    void selfLoopDetectionOnlyComparesKindAndId() {
        // a flow between the same id but different kinds is allowed
        LogicalFlow flow = mkFlow(1L, false);
        when(logicalFlowDao.addFlow(any(LogicalFlow.class))).thenReturn(flow);
        when(dataTypeService.getUnknownDataType()).thenReturn(Optional.empty());

        service.addFlow(
                ImmutableAddLogicalFlowCommand
                        .builder()
                        .source(mkRef(EntityKind.APPLICATION, 1L))
                        .target(mkRef(EntityKind.ACTOR, 1L))
                        .build(),
                "tester");

        verify(logicalFlowDao).addFlow(any(LogicalFlow.class));
    }


    @Test
    void addFlowAddsAnUnknownDataTypeDecorationAndAuditsTheChange() {
        LogicalFlow flow = mkFlow(50L, false);
        DataType unknownDataType = ImmutableDataType
                .builder()
                .id(7L)
                .name("Unknown")
                .description("d")
                .code("UNKNOWN")
                .unknown(true)
                .build();

        when(logicalFlowDao.addFlow(any(LogicalFlow.class))).thenReturn(flow);
        when(dataTypeService.getUnknownDataType()).thenReturn(Optional.of(unknownDataType));

        assertSame(flow, service.addFlow(
                ImmutableAddLogicalFlowCommand.builder().source(source).target(target).build(),
                "tester"));

        ArgumentCaptor<List<DataTypeDecorator>> captor = ArgumentCaptor.forClass(List.class);
        verify(logicalFlowDecoratorDao).addDecorators(captor.capture());
        DataTypeDecorator decorator = captor.getValue().get(0);

        assertEquals(mkRef(EntityKind.DATA_TYPE, 7L), decorator.decoratorEntity());

        // SUSPECTED BUG (characterized, not fixed): the decorator's owning
        // entity is built as mkRef(DATA_TYPE, flowId) - the *flow* id carrying
        // the DATA_TYPE kind - rather than mkRef(LOGICAL_DATA_FLOW, flowId).
        assertEquals(mkRef(EntityKind.DATA_TYPE, 50L), decorator.entityReference());

        verify(changeLogService).writeChangeLogEntries(flow, "tester", "Added", Operation.ADD);
    }


    @Test
    void addFlowStillSucceedsWhenThereIsNoUnknownDataType() {
        LogicalFlow flow = mkFlow(50L, false);
        when(logicalFlowDao.addFlow(any(LogicalFlow.class))).thenReturn(flow);
        when(dataTypeService.getUnknownDataType()).thenReturn(Optional.empty());

        service.addFlow(
                ImmutableAddLogicalFlowCommand.builder().source(source).target(target).build(),
                "tester");

        verifyNoInteractions(logicalFlowDecoratorDao);
    }


    @Test
    void updateReadOnlyIsANoOpWhenTheFlagIsUnchanged() {
        LogicalFlow flow = mkFlow(1L, true);
        when(logicalFlowDao.getByFlowId(1L)).thenReturn(flow);

        assertSame(flow, service.updateReadOnly(1L, true, "tester"));

        verify(logicalFlowDao, never()).updateReadOnly(anyLong(), anyBoolean(), anyString());
        verifyNoInteractions(changeLogService);
    }


    @Test
    void updateReadOnlyReturnsNullForAnUnknownFlow() {
        when(logicalFlowDao.getByFlowId(99L)).thenReturn(null);

        assertNull(service.updateReadOnly(99L, true, "tester"));
    }


    @Test
    void updateReadOnlyAuditsTheChangeUsingAHardCodedSupportUser() {
        when(logicalFlowDao.getByFlowId(1L))
                .thenReturn(mkFlow(1L, false))
                .thenReturn(mkFlow(1L, true));

        LogicalFlow updated = service.updateReadOnly(1L, true, "tester");

        assertTrue(updated.isReadOnly());
        verify(logicalFlowDao).updateReadOnly(1L, true, "tester");

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService).write(captor.capture());

        // note: the message always claims waltz_support made the change even
        // though the acting user is recorded separately
        assertEquals("Set to read only by waltz_support.", captor.getValue().message());
        assertEquals("tester", captor.getValue().userId());
    }


    @Test
    void removeFlowRefusesToRemoveAFlowWhichDoesNotExist() {
        when(logicalFlowDao.getByFlowId(99L)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.removeFlow(99L, "tester"));

        assertEquals("Cannot find flow with id: 99, no logical flow removed", ex.getMessage());
        verify(logicalFlowDao, never()).removeFlow(anyLong(), anyString());
    }


    @Test
    void removeFlowRecalculatesUsageForBothEndpointsAndAuditsTheDataTypes() {
        when(logicalFlowDao.getByFlowId(1L)).thenReturn(mkFlow(1L, false));
        when(logicalFlowDao.removeFlow(1L, "tester")).thenReturn(1);
        when(dataTypeService.findByIdSelector(any(Select.class)))
                .thenReturn(singletonList(mkRef(EntityKind.DATA_TYPE, 7L, "Trades")));

        assertEquals(1, service.removeFlow(1L, "tester"));

        verify(dataTypeUsageService).recalculateForApplications(asSet(source, target));
        verify(changeLogService).writeChangeLogEntries(
                mkFlow(1L, false),
                "tester",
                "Removed : datatypes [Trades]",
                Operation.REMOVE);
    }


    @Test
    void calculateStatsRejectsSelectorKindsItCannotHandle() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> service.calculateStats(IdSelectionOptions.mkOpts(mkRef(EntityKind.LOGICAL_DATA_FLOW, 1L))));
    }


    @Test
    void findUpstreamFlowsForEmptyReferencesDoesNotHitTheDao() {
        assertEquals(emptyList(), service.findUpstreamFlowsForEntityReferences(emptyList()));
        assertEquals(emptyList(), service.findUpstreamFlowsForEntityReferences(null));

        verifyNoInteractions(logicalFlowDao);
    }


    @Test
    void restoreFlowOnlyAuditsWhenTheDaoReportsSuccess() {
        when(logicalFlowDao.restoreFlow(1L, "tester")).thenReturn(true);
        when(logicalFlowDao.restoreFlow(2L, "tester")).thenReturn(false);

        assertTrue(service.restoreFlow(1L, "tester"));
        assertFalse(service.restoreFlow(2L, "tester"));

        verify(changeLogService).writeChangeLogEntries(
                mkRef(EntityKind.LOGICAL_DATA_FLOW, 1L),
                "tester",
                "Restored",
                Operation.ADD);
        verify(changeLogService, never()).writeChangeLogEntries(
                mkRef(EntityKind.LOGICAL_DATA_FLOW, 2L),
                "tester",
                "Restored",
                Operation.ADD);
    }


    @Test
    void readsAndCleanupsAreDirectDelegations() {
        List<LogicalFlow> flows = singletonList(mkFlow(1L, false));

        when(logicalFlowDao.findByEntityReference(source)).thenReturn(flows);
        when(logicalFlowDao.findActiveByFlowIds(asSet(1L))).thenReturn(flows);
        when(logicalFlowDao.getByFlowId(1L)).thenReturn(mkFlow(1L, false));
        when(logicalFlowDao.getByFlowExternalId("ext")).thenReturn(mkFlow(1L, false));
        when(logicalFlowDao.cleanupOrphans()).thenReturn(3);
        when(logicalFlowDao.cleanupSelfReferencingFlows()).thenReturn(4);

        assertSame(flows, service.findByEntityReference(source));
        assertSame(flows, service.findActiveByFlowIds(asSet(1L)));
        assertEquals(mkFlow(1L, false), service.getById(1L));
        assertEquals(mkFlow(1L, false), service.getByExternalId("ext"));
        assertEquals(3, service.cleanupOrphans());
        assertEquals(4, service.cleanupSelfReferencingFlows());
    }
}
