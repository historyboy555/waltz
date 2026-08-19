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

package org.finos.waltz.service.physical_flow;

import org.finos.waltz.common.exception.ModifyingReadOnlyRecordException;
import org.finos.waltz.common.exception.NotFoundException;
import org.finos.waltz.data.physical_flow.PhysicalFlowDao;
import org.finos.waltz.model.physical_flow.CriticalityValue;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityLifecycleStatus;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.physical_flow.FrequencyKindValue;
import org.finos.waltz.model.ImmutableSetAttributeCommand;
import org.finos.waltz.model.Operation;
import org.finos.waltz.model.physical_flow.TransportKindValue;
import org.finos.waltz.model.command.CommandOutcome;
import org.finos.waltz.model.physical_specification.DataFormatKindValue;
import org.finos.waltz.model.logical_flow.ImmutableLogicalFlow;
import org.finos.waltz.model.logical_flow.LogicalFlow;
import org.finos.waltz.model.physical_flow.ImmutableFlowAttributes;
import org.finos.waltz.model.physical_flow.ImmutablePhysicalFlow;
import org.finos.waltz.model.physical_flow.ImmutablePhysicalFlowCreateCommand;
import org.finos.waltz.model.physical_flow.ImmutablePhysicalFlowDeleteCommand;
import org.finos.waltz.model.physical_flow.PhysicalFlow;
import org.finos.waltz.model.physical_flow.PhysicalFlowCreateCommand;
import org.finos.waltz.model.physical_flow.PhysicalFlowCreateCommandResponse;
import org.finos.waltz.model.physical_flow.PhysicalFlowDeleteCommandResponse;
import org.finos.waltz.model.physical_specification.ImmutablePhysicalSpecification;
import org.finos.waltz.model.physical_specification.PhysicalSpecification;
import org.finos.waltz.service.changelog.ChangeLogService;
import org.finos.waltz.service.data_type.DataTypeDecoratorService;
import org.finos.waltz.service.external_identifier.ExternalIdentifierService;
import org.finos.waltz.service.logical_flow.LogicalFlowService;
import org.finos.waltz.service.permission.permission_checker.FlowPermissionChecker;
import org.finos.waltz.service.physical_specification.PhysicalSpecificationService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.finos.waltz.common.SetUtilities.asSet;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link PhysicalFlowService}, concentrating on the
 * create / delete command outcomes and the attribute update dispatch.
 */
class PhysicalFlowServiceTest {

    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final LogicalFlowService logicalFlowService = mock(LogicalFlowService.class);
    private final PhysicalFlowDao physicalFlowDao = mock(PhysicalFlowDao.class);
    private final PhysicalSpecificationService physicalSpecificationService = mock(PhysicalSpecificationService.class);
    private final ExternalIdentifierService externalIdentifierService = mock(ExternalIdentifierService.class);
    private final DataTypeDecoratorService dataTypeDecoratorService = mock(DataTypeDecoratorService.class);
    private final FlowPermissionChecker flowPermissionChecker = mock(FlowPermissionChecker.class);

    private final PhysicalFlowService service = mkService();

    private static final LocalDateTime FIXED_DATE = LocalDateTime.of(2020, 1, 1, 0, 0);


    private PhysicalFlowService mkService() {
        return new PhysicalFlowService(
                changeLogService,
                logicalFlowService,
                physicalFlowDao,
                physicalSpecificationService,
                externalIdentifierService,
                dataTypeDecoratorService,
                flowPermissionChecker);
    }


    private PhysicalFlow mkFlow(long id, boolean isReadOnly) {
        return ImmutablePhysicalFlow
                .builder()
                .id(id)
                .logicalFlowId(100L)
                .specificationId(200L)
                .name("nightly extract")
                .description("d")
                .basisOffset(0)
                .frequency(FrequencyKindValue.of("DAILY"))
                .transport(TransportKindValue.of("FILE_TRANSPORT"))
                .criticality(CriticalityValue.of("MEDIUM"))
                .isReadOnly(isReadOnly)
                .lastUpdatedBy("tester")
                .lastUpdatedAt(FIXED_DATE)
                .build();
    }


    private PhysicalSpecification mkSpec(Optional<Long> id, boolean isRemoved) {
        return ImmutablePhysicalSpecification
                .builder()
                .id(id)
                .owningEntity(mkRef(EntityKind.APPLICATION, 1L, "App A"))
                .format(DataFormatKindValue.of("FLAT_FILE"))
                .name("spec")
                .description("d")
                .isRemoved(isRemoved)
                .lastUpdatedBy("tester")
                .lastUpdatedAt(FIXED_DATE)
                .provenance("waltz")
                .build();
    }


    private PhysicalFlowCreateCommand mkCreateCmd(PhysicalSpecification spec) {
        return ImmutablePhysicalFlowCreateCommand
                .builder()
                .specification(spec)
                .logicalFlowId(100L)
                .flowAttributes(ImmutableFlowAttributes
                        .builder()
                        .name("nightly extract")
                        .transport(TransportKindValue.of("FILE_TRANSPORT"))
                        .frequency(FrequencyKindValue.of("DAILY"))
                        .criticality(CriticalityValue.of("MEDIUM"))
                        .basisOffset(0)
                        .build())
                .build();
    }


    private LogicalFlow mkLogicalFlow(EntityLifecycleStatus status) {
        return ImmutableLogicalFlow
                .builder()
                .id(100L)
                .source(mkRef(EntityKind.APPLICATION, 1L, "App A"))
                .target(mkRef(EntityKind.APPLICATION, 2L, "App B"))
                .entityLifecycleStatus(status)
                .lastUpdatedBy("tester")
                .lastUpdatedAt(FIXED_DATE)
                .build();
    }


    @Test
    void constructionRejectsNullCollaborators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PhysicalFlowService(
                        null,
                        logicalFlowService,
                        physicalFlowDao,
                        physicalSpecificationService,
                        externalIdentifierService,
                        dataTypeDecoratorService,
                        flowPermissionChecker));
    }


    @Test
    void readsRejectNullReferencesAndOtherwiseDelegate() {
        assertThrows(IllegalArgumentException.class, () -> service.findByEntityReference(null));
        assertThrows(IllegalArgumentException.class, () -> service.findByProducerEntityReference(null));
        assertThrows(IllegalArgumentException.class, () -> service.findByConsumerEntityReference(null));
        assertThrows(IllegalArgumentException.class, () -> service.search(null));

        EntityReference appRef = mkRef(EntityKind.APPLICATION, 1L);
        when(physicalFlowDao.findByEntityReference(appRef)).thenReturn(singletonList(mkFlow(1L, false)));
        when(physicalFlowDao.getById(1L)).thenReturn(mkFlow(1L, false));
        when(physicalFlowDao.cleanupOrphans()).thenReturn(2);
        when(physicalFlowDao.findPhysicalFlowIdsWithProblematicDataTypes(asSet(1L))).thenReturn(asSet(5L));

        assertEquals(singletonList(mkFlow(1L, false)), service.findByEntityReference(appRef));
        assertEquals(mkFlow(1L, false), service.getById(1L));
        assertEquals(2, service.cleanupOrphans());
        assertEquals(asSet(5L), service.findPhysicalFlowIdsWithProblematicDataTypes(asSet(1L)));
    }


    @Test
    void deleteOfAnUnknownFlowFailsWithoutTouchingTheDao() {
        when(physicalFlowDao.getByIdAndIsRemoved(1L, false)).thenReturn(null);

        PhysicalFlowDeleteCommandResponse response = service.delete(
                ImmutablePhysicalFlowDeleteCommand.builder().flowId(1L).build(),
                "tester");

        assertEquals(CommandOutcome.FAILURE, response.outcome());
        assertEquals(Optional.of("Flow not found"), response.message());
        verify(physicalFlowDao, never()).delete(anyLong());
        verifyNoInteractions(changeLogService);
    }


    @Test
    void deleteOfAReadOnlyFlowFailsAndIsNotAudited() {
        when(physicalFlowDao.getByIdAndIsRemoved(1L, false)).thenReturn(mkFlow(1L, true));
        when(physicalFlowDao.delete(1L)).thenReturn(0);

        PhysicalFlowDeleteCommandResponse response = service.delete(
                ImmutablePhysicalFlowDeleteCommand.builder().flowId(1L).build(),
                "tester");

        assertEquals(CommandOutcome.FAILURE, response.outcome());
        assertEquals(
                Optional.of("This flow cannot be deleted as it has been marked as read only"),
                response.message());
        verifyNoInteractions(changeLogService);
    }


    @Test
    void successfulDeleteReportsSpecificationAndLogicalFlowUsageAndAudits() {
        PhysicalFlow flow = mkFlow(1L, false);
        when(physicalFlowDao.getByIdAndIsRemoved(1L, false)).thenReturn(flow);
        when(physicalFlowDao.delete(1L)).thenReturn(1);
        when(physicalSpecificationService.isUsed(200L)).thenReturn(false);
        when(physicalFlowDao.hasPhysicalFlows(100L)).thenReturn(false);

        PhysicalFlowDeleteCommandResponse response = service.delete(
                ImmutablePhysicalFlowDeleteCommand.builder().flowId(1L).build(),
                "tester");

        assertEquals(CommandOutcome.SUCCESS, response.outcome());
        assertTrue(response.isSpecificationUnused());
        assertTrue(response.isLastPhysicalFlow());
        verify(externalIdentifierService).delete(flow.entityReference());
        verify(changeLogService).writeChangeLogEntries(flow, "tester", " removed", Operation.REMOVE);
    }


    @Test
    void createRejectsAnUnknownLogicalFlow() {
        when(logicalFlowService.getById(100L)).thenReturn(null);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(mkCreateCmd(mkSpec(Optional.of(200L), false)), "tester"));

        assertEquals("Unknown logical flow: 100", ex.getMessage());
    }


    @Test
    void createSilentlyRestoresARemovedLogicalFlow() {
        when(logicalFlowService.getById(100L)).thenReturn(mkLogicalFlow(EntityLifecycleStatus.REMOVED));
        when(physicalFlowDao.findByAttributesAndSpecification(any(PhysicalFlow.class)))
                .thenReturn(singletonList(mkFlow(9L, false)));

        service.create(mkCreateCmd(mkSpec(Optional.of(200L), false)), "tester");

        // SUSPECTED ODDITY (characterized, not fixed): a create against a
        // logically removed flow resurrects that flow as a side effect, even
        // when the create itself then fails as a duplicate.
        verify(logicalFlowService).restoreFlow(100L, "tester");
    }


    @Test
    void createReportsDuplicatesAndPointsAtTheExistingFlow() {
        when(logicalFlowService.getById(100L)).thenReturn(mkLogicalFlow(EntityLifecycleStatus.ACTIVE));
        when(physicalFlowDao.findByAttributesAndSpecification(any(PhysicalFlow.class)))
                .thenReturn(singletonList(mkFlow(9L, false)));

        PhysicalFlowCreateCommandResponse response = service.create(
                mkCreateCmd(mkSpec(Optional.of(200L), false)),
                "tester");

        assertEquals(CommandOutcome.FAILURE, response.outcome());
        assertEquals(Optional.of("Duplicate with existing flow"), response.message());
        assertEquals(mkRef(EntityKind.PHYSICAL_FLOW, 9L), response.entityReference());
        assertEquals(200L, response.specificationId());
        verify(physicalFlowDao, never()).create(any(PhysicalFlow.class));
        verifyNoInteractions(changeLogService);
    }


    @Test
    void createUsesTheGivenSpecificationIdAndDoesNotCreateASpecification() {
        when(logicalFlowService.getById(100L)).thenReturn(mkLogicalFlow(EntityLifecycleStatus.ACTIVE));
        when(physicalFlowDao.findByAttributesAndSpecification(any(PhysicalFlow.class))).thenReturn(emptyList());
        when(physicalFlowDao.create(any(PhysicalFlow.class))).thenReturn(42L);

        PhysicalFlowCreateCommandResponse response = service.create(
                mkCreateCmd(mkSpec(Optional.of(200L), false)),
                "tester");

        assertEquals(CommandOutcome.SUCCESS, response.outcome());
        assertEquals(mkRef(EntityKind.PHYSICAL_FLOW, 42L), response.entityReference());
        assertEquals(200L, response.specificationId());

        verify(physicalSpecificationService, never()).create(any(ImmutablePhysicalSpecification.class));
        verify(physicalSpecificationService, never()).makeActive(anyLong(), any());
        verify(changeLogService).writeChangeLogEntries(
                any(PhysicalFlow.class),
                org.mockito.ArgumentMatchers.eq("tester"),
                org.mockito.ArgumentMatchers.eq(" created"),
                org.mockito.ArgumentMatchers.eq(Operation.ADD));
    }


    @Test
    void createBuildsAMissingSpecificationAndReactivatesRemovedOnes() {
        when(logicalFlowService.getById(100L)).thenReturn(mkLogicalFlow(EntityLifecycleStatus.ACTIVE));
        when(physicalSpecificationService.create(any(ImmutablePhysicalSpecification.class))).thenReturn(77L);
        when(physicalFlowDao.findByAttributesAndSpecification(any(PhysicalFlow.class))).thenReturn(emptyList());
        when(physicalFlowDao.create(any(PhysicalFlow.class))).thenReturn(42L);

        PhysicalFlowCreateCommandResponse response = service.create(
                mkCreateCmd(mkSpec(Optional.empty(), true)),
                "tester");

        assertEquals(77L, response.specificationId());
        verify(physicalSpecificationService).makeActive(77L, "tester");
        verifyNoInteractions(dataTypeDecoratorService);
    }


    @Test
    void createCascadesDataTypesOntoTheSpecification() {
        when(logicalFlowService.getById(100L)).thenReturn(mkLogicalFlow(EntityLifecycleStatus.ACTIVE));
        when(physicalFlowDao.findByAttributesAndSpecification(any(PhysicalFlow.class))).thenReturn(emptyList());
        when(physicalFlowDao.create(any(PhysicalFlow.class))).thenReturn(42L);

        service.create(
                ImmutablePhysicalFlowCreateCommand
                        .copyOf(mkCreateCmd(mkSpec(Optional.of(200L), false)))
                        .withDataTypeIds(asSet(11L)),
                "tester");

        verify(dataTypeDecoratorService).updateDecorators(
                "tester",
                mkRef(EntityKind.PHYSICAL_SPECIFICATION, 200L),
                asSet(11L),
                asSet());
    }


    @Test
    void updateSpecDefinitionIdOnlyAuditsWhenSomethingChanged() {
        when(physicalFlowDao.updateSpecDefinition("tester", 1L, 5L)).thenReturn(1);
        when(physicalFlowDao.updateSpecDefinition("tester", 2L, 5L)).thenReturn(0);

        assertEquals(1, service.updateSpecDefinitionId(
                "tester",
                1L,
                org.finos.waltz.model.physical_flow.ImmutablePhysicalFlowSpecDefinitionChangeCommand
                        .builder()
                        .newSpecDefinitionId(5L)
                        .build()));
        assertEquals(0, service.updateSpecDefinitionId(
                "tester",
                2L,
                org.finos.waltz.model.physical_flow.ImmutablePhysicalFlowSpecDefinitionChangeCommand
                        .builder()
                        .newSpecDefinitionId(5L)
                        .build()));

        verify(changeLogService).writeChangeLogEntries(
                mkRef(EntityKind.PHYSICAL_FLOW, 1L),
                "tester",
                "Physical flow id: 1 specification definition id changed to: 5",
                Operation.UPDATE);
        verify(changeLogService, never()).writeChangeLogEntries(
                mkRef(EntityKind.PHYSICAL_FLOW, 2L),
                "tester",
                "Physical flow id: 2 specification definition id changed to: 5",
                Operation.UPDATE);
    }


    @Test
    void updateAttributeRejectsUnknownFlowsAndReadOnlyFlows() {
        when(physicalFlowDao.getById(1L)).thenReturn(null);
        when(physicalFlowDao.getById(2L)).thenReturn(mkFlow(2L, true));

        assertThrows(
                NotFoundException.class,
                () -> service.updateAttribute("tester", mkAttributeCmd(1L, "criticality", "HIGH")));
        assertThrows(
                ModifyingReadOnlyRecordException.class,
                () -> service.updateAttribute("tester", mkAttributeCmd(2L, "criticality", "HIGH")));
    }


    @Test
    void updateAttributeRejectsUnknownAttributeNames() {
        when(physicalFlowDao.getById(1L)).thenReturn(mkFlow(1L, false));

        UnsupportedOperationException ex = assertThrows(
                UnsupportedOperationException.class,
                () -> service.updateAttribute("tester", mkAttributeCmd(1L, "wibble", "x")));

        assertEquals(
                "Cannot update attribute wibble on flow as unknown attribute name",
                ex.getMessage());
    }


    @Test
    void updateAttributeDispatchesToTheDaoAndAuditsSuccessfulUpdates() {
        when(physicalFlowDao.getById(1L)).thenReturn(mkFlow(1L, false));
        when(physicalFlowDao.updateCriticality(1L, CriticalityValue.of("HIGH"))).thenReturn(1);
        when(physicalFlowDao.updateFrequency(1L, FrequencyKindValue.of("WEEKLY"))).thenReturn(1);
        when(physicalFlowDao.updateTransport(1L, "OTHER")).thenReturn(1);
        when(physicalFlowDao.updateBasisOffset(1L, 3)).thenReturn(1);
        when(physicalFlowDao.updateDescription(1L, "notes")).thenReturn(0);

        assertEquals(1, service.updateAttribute("tester", mkAttributeCmd(1L, "criticality", "HIGH")));
        assertEquals(1, service.updateAttribute("tester", mkAttributeCmd(1L, "frequency", "WEEKLY")));
        assertEquals(1, service.updateAttribute("tester", mkAttributeCmd(1L, "transport", "OTHER")));
        assertEquals(1, service.updateAttribute("tester", mkAttributeCmd(1L, "basisOffset", "3")));
        assertEquals(0, service.updateAttribute("tester", mkAttributeCmd(1L, "description", "notes")));

        verify(changeLogService).writeChangeLogEntries(
                mkRef(EntityKind.PHYSICAL_FLOW, 1L),
                "tester",
                "Updated attribute criticality to HIGH",
                Operation.UPDATE);
        // a no-op update is not audited
        verify(changeLogService, never()).writeChangeLogEntries(
                mkRef(EntityKind.PHYSICAL_FLOW, 1L),
                "tester",
                "Updated attribute description to notes",
                Operation.UPDATE);
    }


    @Test
    void mergeBlowsUpWhenTheSourceFlowCannotBeFound() {
        when(physicalFlowDao.getById(1L)).thenReturn(null);

        // SUSPECTED BUG (characterized, not fixed): merge dereferences the
        // source flow without checking it exists, so merging from a
        // non-existent flow id fails with a NullPointerException rather than a
        // meaningful error - after the external id merge has already run.
        assertThrows(NullPointerException.class, () -> service.merge(1L, 2L, "tester"));
        verify(externalIdentifierService).merge(
                mkRef(EntityKind.PHYSICAL_FLOW, 1L),
                mkRef(EntityKind.PHYSICAL_FLOW, 2L));
    }


    @Test
    void getPhysicalFlowIfExistIsNullWhenTheSpecificationHasNoId() {
        assertNull(service.getPhysicalFlowIfExist(mkCreateCmd(mkSpec(Optional.empty(), false)), "tester"));

        verifyNoInteractions(physicalFlowDao);
    }


    private org.finos.waltz.model.SetAttributeCommand mkAttributeCmd(long flowId, String name, String value) {
        return ImmutableSetAttributeCommand
                .builder()
                .entityReference(mkRef(EntityKind.PHYSICAL_FLOW, flowId))
                .name(name)
                .value(value)
                .build();
    }
}
