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

package org.finos.waltz.service.entity_hierarchy;

import org.finos.waltz.data.change_initiative.ChangeInitiativeDao;
import org.finos.waltz.data.data_type.DataTypeDao;
import org.finos.waltz.data.entity_hierarchy.EntityHierarchyDao;
import org.finos.waltz.data.entity_statistic.EntityStatisticDao;
import org.finos.waltz.data.measurable.MeasurableDao;
import org.finos.waltz.data.orgunit.OrganisationalUnitDao;
import org.finos.waltz.data.person.PersonDao;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.entity_hierarchy.EntityHierarchy;
import org.finos.waltz.model.entity_hierarchy.EntityHierarchyItem;
import org.finos.waltz.model.entity_hierarchy.ImmutableEntityHierarchyItem;
import org.finos.waltz.model.tally.ImmutableTally;
import org.finos.waltz.model.tally.Tally;
import org.finos.waltz.service.person_hierarchy.PersonHierarchyService;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Select;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link EntityHierarchyService}.  The service mixes
 * kind based dispatch (getRoots / buildFor) with a small amount of assembly
 * logic (tallies, wrapping hierarchy items).  These tests pin the dispatch
 * table and the current failure modes for unsupported entity kinds.
 */
class EntityHierarchyServiceTest {

    private final DSLContext dsl = mock(DSLContext.class);
    private final ChangeInitiativeDao changeInitiativeDao = mock(ChangeInitiativeDao.class);
    private final DataTypeDao dataTypeDao = mock(DataTypeDao.class);
    private final EntityHierarchyDao entityHierarchyDao = mock(EntityHierarchyDao.class);
    private final EntityStatisticDao entityStatisticDao = mock(EntityStatisticDao.class);
    private final MeasurableDao measurableDao = mock(MeasurableDao.class);
    private final OrganisationalUnitDao organisationalUnitDao = mock(OrganisationalUnitDao.class);
    private final PersonHierarchyService personHierarchyService = mock(PersonHierarchyService.class);
    private final PersonDao personDao = mock(PersonDao.class);

    private final EntityHierarchyService service = new EntityHierarchyService(
            dsl,
            changeInitiativeDao,
            dataTypeDao,
            entityHierarchyDao,
            entityStatisticDao,
            measurableDao,
            organisationalUnitDao,
            personHierarchyService,
            personDao);


    private static Tally<String> mkTally(String id, double count) {
        return ImmutableTally.<String>builder()
                .id(id)
                .count(count)
                .build();
    }


    @Test
    void constructionRejectsNullCollaborators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EntityHierarchyService(
                        null,
                        changeInitiativeDao,
                        dataTypeDao,
                        entityHierarchyDao,
                        entityStatisticDao,
                        measurableDao,
                        organisationalUnitDao,
                        personHierarchyService,
                        personDao));
    }


    @Test
    void tallyByKindAlwaysAppendsAPersonTallyFromThePersonHierarchyService() {
        when(entityHierarchyDao.tallyByKind()).thenReturn(singletonList(mkTally("MEASURABLE", 3)));
        when(personHierarchyService.count()).thenReturn(7);

        List<Tally<String>> tallies = service.tallyByKind();

        assertEquals(2, tallies.size());
        assertEquals(mkTally("MEASURABLE", 3), tallies.get(0));
        assertEquals(mkTally("PERSON", 7), tallies.get(1), "person tally comes from the person hierarchy service");
    }


    @Test
    void getRootTalliesAlsoAppendsThePersonRootCount() {
        when(entityHierarchyDao.getRootTallies()).thenReturn(emptyList());
        when(personHierarchyService.countRoots()).thenReturn(2d);

        List<Tally<String>> tallies = service.getRootTallies();

        assertEquals(singletonList(mkTally("PERSON", 2)), tallies);
    }


    @Test
    void getRootsDispatchesToTheDaoMatchingTheEntityKind() {
        List<EntityReference> refs = singletonList(mkRef(EntityKind.MEASURABLE, 1L));

        when(changeInitiativeDao.findByIdSelectorAsEntityReference(any())).thenReturn(refs);
        when(dataTypeDao.findByIdSelectorAsEntityReference(any())).thenReturn(refs);
        when(entityStatisticDao.findByIdSelectorAsEntityReference(any())).thenReturn(refs);
        when(measurableDao.findByIdSelectorAsEntityReference(any())).thenReturn(refs);
        when(organisationalUnitDao.findByIdSelectorAsEntityReference(any())).thenReturn(refs);
        when(personDao.findByPersonIdSelectorAsEntityReference(any())).thenReturn(refs);

        assertEquals(refs, service.getRoots(EntityKind.CHANGE_INITIATIVE));
        assertEquals(refs, service.getRoots(EntityKind.DATA_TYPE));
        assertEquals(refs, service.getRoots(EntityKind.ENTITY_STATISTIC));
        assertEquals(refs, service.getRoots(EntityKind.MEASURABLE));
        assertEquals(refs, service.getRoots(EntityKind.ORG_UNIT));
        assertEquals(refs, service.getRoots(EntityKind.PERSON));

        verifyNoInteractions(dsl);
    }


    @Test
    void getRootsThrowsForKindsWithoutAHierarchy() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.getRoots(EntityKind.APPLICATION));

        assertTrue(ex.getMessage().contains("APPLICATION"));
    }


    @Test
    void buildForPersonReturnsTheNumberOfStatementResultsRatherThanARowCount() {
        // SUSPECTED ODDITY (characterized, not fixed): for every other kind
        // buildFor returns the number of hierarchy rows written, but for PERSON
        // it returns the *length of the batch result array* from
        // PersonHierarchyService.build(), i.e. a different unit entirely.
        when(personHierarchyService.build()).thenReturn(new int[]{10, 20, 30});

        assertEquals(3, service.buildFor(EntityKind.PERSON));
    }


    @Test
    void buildForThrowsForKindsWhichHaveNoHierarchyTable() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.buildFor(EntityKind.APPLICATION));

        assertTrue(ex.getMessage().startsWith("Cannot determine hierarchy table for kind"));
    }


    @Test
    void fetchHierarchyForKindWrapsTheDaoItemsWithoutFiltering() {
        EntityHierarchyItem item = ImmutableEntityHierarchyItem
                .builder()
                .id(1L)
                .parentId(1L)
                .ancestorLevel(1)
                .descendantLevel(1)
                .kind(EntityKind.MEASURABLE)
                .build();

        when(entityHierarchyDao.fetchHierarchyForKind(EntityKind.MEASURABLE)).thenReturn(singletonList(item));

        EntityHierarchy hierarchy = service.fetchHierarchyForKind(EntityKind.MEASURABLE);

        assertEquals(singletonList(item), hierarchy.hierarchyItems());
    }
}
