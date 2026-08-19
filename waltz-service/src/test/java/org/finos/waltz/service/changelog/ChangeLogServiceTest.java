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
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.Operation;
import org.finos.waltz.model.Severity;
import org.finos.waltz.model.changelog.ChangeLog;
import org.finos.waltz.model.changelog.ImmutableChangeLog;
import org.finos.waltz.model.logical_flow.ImmutableLogicalFlow;
import org.finos.waltz.model.logical_flow.LogicalFlow;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Date;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toSet;
import static org.finos.waltz.common.SetUtilities.asSet;
import static org.finos.waltz.common.SetUtilities.map;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link ChangeLogService}, the audit seam used by
 * (almost) every mutating service in Waltz.  The interesting logic is the
 * kind-based dispatch in
 * {@code writeChangeLogEntries(EntityReference, ..)} and the derivation of the
 * set of parent entities which each audit message is fanned out to.
 */
class ChangeLogServiceTest {

    private final ChangeLogDao changeLogDao = mock(ChangeLogDao.class);
    private final ChangeLogSummariesDao changeLogSummariesDao = mock(ChangeLogSummariesDao.class);
    private final PhysicalFlowDao physicalFlowDao = mock(PhysicalFlowDao.class);
    private final PhysicalSpecificationDao physicalSpecificationDao = mock(PhysicalSpecificationDao.class);
    private final LogicalFlowDao logicalFlowDao = mock(LogicalFlowDao.class);
    private final ApplicationDao applicationDao = mock(ApplicationDao.class);
    private final MeasurableRatingReplacementDao measurableRatingReplacementDao = mock(MeasurableRatingReplacementDao.class);
    private final MeasurableRatingDao measurableRatingDao = mock(MeasurableRatingDao.class);
    private final MeasurableRatingPlannedDecommissionDao measurableRatingPlannedDecommissionDao = mock(MeasurableRatingPlannedDecommissionDao.class);
    private final EntityReferenceNameResolver nameResolver = mock(EntityReferenceNameResolver.class);

    private final ChangeLogService service = new ChangeLogService(
            changeLogDao,
            changeLogSummariesDao,
            physicalFlowDao,
            physicalSpecificationDao,
            logicalFlowDao,
            applicationDao,
            measurableRatingReplacementDao,
            measurableRatingDao,
            measurableRatingPlannedDecommissionDao,
            nameResolver);

    private final EntityReference source = mkRef(EntityKind.APPLICATION, 1L, "App A");
    private final EntityReference target = mkRef(EntityKind.APPLICATION, 2L, "App B");

    private final LogicalFlow logicalFlow = ImmutableLogicalFlow
            .builder()
            .id(100L)
            .source(source)
            .target(target)
            .lastUpdatedBy("tester")
            .build();


    @Test
    void constructionRejectsNullCollaborators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChangeLogService(
                        null,
                        changeLogSummariesDao,
                        physicalFlowDao,
                        physicalSpecificationDao,
                        logicalFlowDao,
                        applicationDao,
                        measurableRatingReplacementDao,
                        measurableRatingDao,
                        measurableRatingPlannedDecommissionDao,
                        nameResolver));
    }


    @Test
    void readsAreDelegatedToTheDaoButRequireANonNullReference() {
        EntityReference ref = mkRef(EntityKind.APPLICATION, 1L);
        Date start = Date.valueOf("2020-01-01");
        Date end = Date.valueOf("2020-12-31");
        List<ChangeLog> entries = emptyList();

        when(changeLogDao.findByParentReferenceForDateRange(ref, start, end, Optional.empty())).thenReturn(entries);
        when(changeLogDao.findByPersonReferenceForDateRange(ref, start, end, Optional.empty())).thenReturn(entries);
        when(changeLogDao.findByParentReference(ref, Optional.empty(), Optional.empty())).thenReturn(entries);
        when(changeLogDao.findByPersonReference(ref, Optional.empty(), Optional.empty())).thenReturn(entries);
        when(changeLogDao.findUnattestedChanges(ref)).thenReturn(entries);

        assertSame(entries, service.findByParentReferenceForDateRange(ref, start, end, Optional.empty()));
        assertSame(entries, service.findByPersonReferenceForDateRange(ref, start, end, Optional.empty()));
        assertSame(entries, service.findByParentReference(ref, Optional.empty(), Optional.empty()));
        assertSame(entries, service.findByPersonReference(ref, Optional.empty(), Optional.empty()));
        assertSame(entries, service.findUnattestedChanges(ref));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.findByParentReference(null, Optional.empty(), Optional.empty()));
    }


    @Test
    void findByUserRejectsEmptyUsernames() {
        assertThrows(IllegalArgumentException.class, () -> service.findByUser("", Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> service.findByUser(null, Optional.empty()));
    }


    @Test
    void writeWithoutATransactionPassesAnEmptyTransactionToTheDao() {
        ChangeLog entry = ImmutableChangeLog
                .builder()
                .parentReference(source)
                .message("m")
                .severity(Severity.INFORMATION)
                .userId("tester")
                .operation(Operation.UPDATE)
                .build();

        when(changeLogDao.write(Optional.empty(), entry)).thenReturn(1);

        assertEquals(1, service.write(entry));
        verify(changeLogDao).write(Optional.empty(), entry);
    }


    @Test
    void writeWithATransactionAndBatchWritesAreDirectDelegations() {
        DSLContext tx = mock(DSLContext.class);
        ChangeLog entry = ImmutableChangeLog
                .builder()
                .parentReference(source)
                .message("m")
                .severity(Severity.INFORMATION)
                .userId("tester")
                .operation(Operation.UPDATE)
                .build();

        when(changeLogDao.write(Optional.of(tx), entry)).thenReturn(1);
        when(changeLogDao.write(asSet(entry))).thenReturn(new int[]{1});

        assertEquals(1, service.write(Optional.of(tx), entry));
        assertEquals(1, service.write(asSet(entry)).length);
    }


    @Test
    void writingLogicalFlowEntriesFansOutToTheFlowAndBothEndpoints() {
        service.writeChangeLogEntries(logicalFlow, "tester", "was updated", Operation.UPDATE);

        Collection<ChangeLog> written = captureWrittenEntries();

        assertEquals(
                asSet(logicalFlow.entityReference(), source, target),
                map(written, ChangeLog::parentReference),
                "the flow itself plus its source and target are audited");

        Set<String> messages = written.stream().map(ChangeLog::message).collect(toSet());
        assertEquals(
                asSet("Logical flow from: App A [1], to: App B [2]: was updated"),
                messages);

        written.forEach(cl -> {
            assertEquals(Severity.INFORMATION, cl.severity(), "audit entries are always INFORMATION");
            assertEquals(Optional.of(EntityKind.LOGICAL_DATA_FLOW), cl.childKind());
            assertEquals(Optional.of(100L), cl.childId());
            assertEquals(Operation.UPDATE, cl.operation());
            assertEquals("tester", cl.userId());
        });
    }


    @Test
    void logicalFlowEntriesAreLookedUpByIdWhenDispatchingOnAnEntityReference() {
        when(logicalFlowDao.getByFlowId(100L)).thenReturn(logicalFlow);

        service.writeChangeLogEntries(
                mkRef(EntityKind.LOGICAL_DATA_FLOW, 100L),
                "tester",
                "was removed",
                Operation.REMOVE);

        assertEquals(3, captureWrittenEntries().size());
    }


    @Test
    void dispatchingOnAnUnsupportedEntityKindSilentlyDoesNothing() {
        // note: no exception and no audit trail - callers cannot tell that
        // their change went unrecorded
        service.writeChangeLogEntries(
                mkRef(EntityKind.APPLICATION, 1L),
                "tester",
                "was updated",
                Operation.UPDATE);

        verify(changeLogDao, never()).write(anyCollection());
        verifyNoInteractions(logicalFlowDao, physicalFlowDao, physicalSpecificationDao);
    }


    @Test
    void countByDateForParentKindDelegatesToTheSummariesDao() {
        when(changeLogSummariesDao.findCountByDateForParentKindBySelector(any(), any())).thenReturn(emptyList());

        assertEquals(
                emptyList(),
                service.findCountByDateForParentKindBySelector(
                        EntityKind.APPLICATION,
                        org.finos.waltz.model.IdSelectionOptions.mkOpts(mkRef(EntityKind.APPLICATION, 1L)),
                        Optional.empty()));
    }


    @SuppressWarnings("unchecked")
    private Collection<ChangeLog> captureWrittenEntries() {
        ArgumentCaptor<Collection<ChangeLog>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(changeLogDao).write(captor.capture());
        return captor.getValue();
    }
}
