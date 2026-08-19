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

package org.finos.waltz.service.measurable_rating;

import org.finos.waltz.data.EntityReferenceNameResolver;
import org.finos.waltz.data.application.ApplicationDao;
import org.finos.waltz.data.measurable.MeasurableDao;
import org.finos.waltz.data.measurable_category.MeasurableCategoryDao;
import org.finos.waltz.data.measurable_rating.MeasurableRatingDao;
import org.finos.waltz.data.rating_scheme.RatingSchemeDAO;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.Operation;
import org.finos.waltz.model.UserTimestamp;
import org.finos.waltz.model.changelog.ChangeLog;
import org.finos.waltz.model.measurable.ImmutableMeasurable;
import org.finos.waltz.model.measurable.Measurable;
import org.finos.waltz.model.measurable_category.ImmutableMeasurableCategory;
import org.finos.waltz.model.measurable_category.MeasurableCategory;
import org.finos.waltz.model.measurable_rating.ImmutableMeasurableRatingChangeSummary;
import org.finos.waltz.model.measurable_rating.ImmutableRemoveMeasurableRatingCommand;
import org.finos.waltz.model.measurable_rating.MeasurableRating;
import org.finos.waltz.model.measurable_rating.MeasurableRatingChangeSummary;
import org.finos.waltz.model.measurable_rating.RemoveMeasurableRatingCommand;
import org.finos.waltz.model.rating.ImmutableRatingSchemeItem;
import org.finos.waltz.model.rating.RatingSchemeItem;
import org.finos.waltz.service.application.ApplicationService;
import org.finos.waltz.service.changelog.ChangeLogService;
import org.finos.waltz.service.measurable.MeasurableService;
import org.finos.waltz.service.measurable_category.MeasurableCategoryService;
import org.finos.waltz.service.rating_scheme.RatingSchemeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.jooq.lambda.tuple.Tuple.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link MeasurableRatingService}: the guards on
 * removal, the restricted rating check and the (double) change log entries
 * written for each rating mutation.
 */
class MeasurableRatingServiceTest {

    private final MeasurableRatingDao measurableRatingDao = mock(MeasurableRatingDao.class);
    private final MeasurableDao measurableDao = mock(MeasurableDao.class);
    private final MeasurableCategoryDao measurableCategoryDao = mock(MeasurableCategoryDao.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final RatingSchemeService ratingSchemeService = mock(RatingSchemeService.class);
    private final EntityReferenceNameResolver nameResolver = mock(EntityReferenceNameResolver.class);
    private final MeasurableService measurableService = mock(MeasurableService.class);
    private final ApplicationService applicationService = mock(ApplicationService.class);
    private final MeasurableCategoryService measurableCategoryService = mock(MeasurableCategoryService.class);
    private final ApplicationDao applicationDao = mock(ApplicationDao.class);
    private final RatingSchemeDAO ratingSchemeDAO = mock(RatingSchemeDAO.class);

    private final MeasurableRatingService service = mkService();

    private final EntityReference appRef = mkRef(EntityKind.APPLICATION, 1L, "App A");
    private final EntityReference measurableRef = mkRef(EntityKind.MEASURABLE, 10L, "Trading");
    private final EntityReference categoryRef = mkRef(EntityKind.MEASURABLE_CATEGORY, 100L, "Capability");


    private MeasurableRatingService mkService() {
        return new MeasurableRatingService(
                measurableRatingDao,
                measurableDao,
                measurableCategoryDao,
                changeLogService,
                ratingSchemeService,
                nameResolver,
                measurableService,
                applicationService,
                measurableCategoryService,
                applicationDao,
                ratingSchemeDAO);
    }


    private Measurable mkMeasurable(long id, long categoryId) {
        return ImmutableMeasurable
                .builder()
                .id(id)
                .parentId(java.util.Optional.empty())
                .name("Trading")
                .description("d")
                .categoryId(categoryId)
                .concrete(true)
                .lastUpdatedBy("tester")
                .lastUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0))
                .provenance("waltz")
                .build();
    }


    private MeasurableCategory mkCategory(long id, long ratingSchemeId) {
        return ImmutableMeasurableCategory
                .builder()
                .id(id)
                .name("Capability")
                .description("d")
                .icon("cog")
                .ratingSchemeId(ratingSchemeId)
                .lastUpdatedBy("tester")
                .lastUpdatedAt(LocalDateTime.of(2020, 1, 1, 0, 0))
                .allowPrimaryRatings(true)
                .build();
    }


    private MeasurableRatingChangeSummary mkSummary(boolean hasCurrentRating) {
        ImmutableMeasurableRatingChangeSummary.Builder b = ImmutableMeasurableRatingChangeSummary
                .builder()
                .entityRef(appRef)
                .measurableRef(measurableRef)
                .measurableCategoryRef(categoryRef)
                .desiredRatingNameAndCode(tuple("Good", "G"));

        return hasCurrentRating
                ? b.currentRatingNameAndCode(tuple("Bad", "B")).build()
                : b.build();
    }


    private RemoveMeasurableRatingCommand mkRemoveCmd() {
        return ImmutableRemoveMeasurableRatingCommand
                .builder()
                .entityReference(appRef)
                .measurableId(10L)
                .lastUpdate(UserTimestamp.mkForUser("tester", LocalDateTime.of(2020, 1, 1, 0, 0)))
                .build();
    }


    @Test
    void constructionRejectsNullCollaborators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MeasurableRatingService(
                        null,
                        measurableDao,
                        measurableCategoryDao,
                        changeLogService,
                        ratingSchemeService,
                        nameResolver,
                        measurableService,
                        applicationService,
                        measurableCategoryService,
                        applicationDao,
                        ratingSchemeDAO));
    }


    @Test
    void readsRejectNullArgumentsAndOtherwiseDelegate() {
        assertThrows(IllegalArgumentException.class, () -> service.findForEntity(null));
        assertThrows(IllegalArgumentException.class, () -> service.findByMeasurableIdSelector(null));
        assertThrows(IllegalArgumentException.class, () -> service.findByAppIdSelector(null));
        assertThrows(IllegalArgumentException.class, () -> service.statsByAppSelector(null));

        List<MeasurableRating> ratings = emptyList();
        when(measurableRatingDao.findForEntity(appRef)).thenReturn(ratings);
        when(measurableRatingDao.findByCategory(100L)).thenReturn(ratings);
        when(measurableRatingDao.getById(5L)).thenReturn(null);
        when(measurableRatingDao.getByDecommId(6L)).thenReturn(null);
        when(measurableRatingDao.getSharedRatingsCount(1L, 2L)).thenReturn(7);
        when(measurableRatingDao.getSharedDecommsCount(1L, 2L)).thenReturn(8);
        when(measurableDao.getRequiredRatingEditRole(appRef)).thenReturn("RATING_EDITOR");

        assertEquals(ratings, service.findForEntity(appRef));
        assertEquals(ratings, service.findByCategory(100L));
        assertEquals(7, service.getSharedRatingsCount(1L, 2L));
        assertEquals(8, service.getSharedDecommsCount(1L, 2L));
        assertEquals("RATING_EDITOR", service.getRequiredRatingEditRole(appRef));
    }


    @Test
    void removeForCategoryRefusesToRunAgainstAnUnknownCategory() {
        when(measurableCategoryDao.getById(100L)).thenReturn(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.removeForCategory(appRef, 100L, "tester"));

        verify(measurableRatingDao, never()).removeForCategory(any(), org.mockito.ArgumentMatchers.anyLong());
    }


    @Test
    void removeForCategoryAuditsTheNumberOfRatingsRemoved() {
        when(measurableCategoryDao.getById(100L)).thenReturn(mkCategory(100L, 1L));
        when(measurableRatingDao.removeForCategory(appRef, 100L)).thenReturn(3);

        service.removeForCategory(appRef, 100L, "tester");

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService).write(captor.capture());

        assertEquals(
                "Removed all (3) ratings for category: Capability which are not read-only",
                captor.getValue().message());
        assertEquals(Operation.REMOVE, captor.getValue().operation());
        verify(measurableRatingDao).findForEntity(appRef);
    }


    @Test
    void removeSkipsAuditingWhenTheDaoReportsNothingWasRemoved() {
        when(measurableDao.getById(10L)).thenReturn(mkMeasurable(10L, 100L));
        when(measurableRatingDao.remove(any(RemoveMeasurableRatingCommand.class))).thenReturn(false);

        service.remove(mkRemoveCmd());

        verifyNoInteractions(changeLogService);
    }


    @Test
    void removeSkipsAuditingWhenTheMeasurableIsUnknownButStillPerformsTheRemoval() {
        when(measurableDao.getById(10L)).thenReturn(null);
        when(measurableRatingDao.remove(any(RemoveMeasurableRatingCommand.class))).thenReturn(true);

        service.remove(mkRemoveCmd());

        // SUSPECTED ODDITY (characterized, not fixed): the removal is still
        // executed against the dao when the measurable cannot be resolved,
        // it just silently loses its audit trail.
        verify(measurableRatingDao).remove(any(RemoveMeasurableRatingCommand.class));
        verifyNoInteractions(changeLogService);
    }


    @Test
    void removeWritesAPairOfChangeLogEntriesFromBothPointsOfView() {
        when(measurableDao.getById(10L)).thenReturn(mkMeasurable(10L, 100L));
        when(measurableRatingDao.remove(any(RemoveMeasurableRatingCommand.class))).thenReturn(true);

        service.remove(mkRemoveCmd());

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService, times(2)).write(captor.capture());

        assertEquals("Removed: Trading for App A", captor.getAllValues().get(0).message());
        assertEquals(appRef, captor.getAllValues().get(0).parentReference());
        assertEquals("Removed: App A for Trading", captor.getAllValues().get(1).message());
        assertEquals(measurableRef, captor.getAllValues().get(1).parentReference());
    }


    @Test
    void saveRatingItemRejectsRestrictedRatings() {
        RatingSchemeItem restricted = ImmutableRatingSchemeItem
                .builder()
                .id(1L)
                .name("Restricted")
                .description("d")
                .ratingSchemeId(1L)
                .rating("R")
                .color("red")
                .position(1)
                .isRestricted(true)
                .build();

        when(measurableDao.getById(10L)).thenReturn(mkMeasurable(10L, 100L));
        when(ratingSchemeService.findRatingSchemeItemsForEntityAndCategory(appRef, 100L))
                .thenReturn(singletonList(restricted));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.saveRatingItem(appRef, 10L, "R", "tester"));

        assertEquals("New rating is restricted, rating not saved", ex.getMessage());
        verify(measurableRatingDao, never()).saveRatingItem(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }


    @Test
    void saveRatingItemBlowsUpForAnUnknownMeasurable() {
        when(measurableDao.getById(10L)).thenReturn(null);

        // SUSPECTED BUG (characterized, not fixed): the category is read via
        // `measurableDao.getById(measurableId).categoryId()` with no null
        // check, so an unknown measurable surfaces as a NullPointerException
        // rather than a meaningful error.
        assertThrows(
                NullPointerException.class,
                () -> service.saveRatingItem(appRef, 10L, "G", "tester"));
    }


    @Test
    void saveRatingItemLogsAnAddWhenThereWasNoPreviousRating() {
        when(measurableDao.getById(10L)).thenReturn(mkMeasurable(10L, 100L));
        when(ratingSchemeService.findRatingSchemeItemsForEntityAndCategory(appRef, 100L))
                .thenReturn(emptyList());
        when(measurableRatingDao.resolveLoggingContextForRatingChange(appRef, 10L, "G"))
                .thenReturn(mkSummary(false));
        when(measurableRatingDao.saveRatingItem(appRef, 10L, "G", "tester")).thenReturn(true);

        assertTrue(service.saveRatingItem(appRef, 10L, "G", "tester"));

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService, times(2)).write(captor.capture());

        assertEquals(Operation.ADD, captor.getAllValues().get(0).operation());
        assertEquals(
                "Saving rating for: Trading [10], in category: Capability [100], new value: Good [G], old value: -",
                captor.getAllValues().get(0).message());
        assertEquals(
                "Saving rating for application App A [1], new value: Good [G], old value: -",
                captor.getAllValues().get(1).message());
    }


    @Test
    void saveRatingItemLogsAnUpdateWhenReplacingAnExistingRating() {
        when(measurableDao.getById(10L)).thenReturn(mkMeasurable(10L, 100L));
        when(ratingSchemeService.findRatingSchemeItemsForEntityAndCategory(appRef, 100L))
                .thenReturn(emptyList());
        when(measurableRatingDao.resolveLoggingContextForRatingChange(appRef, 10L, "G"))
                .thenReturn(mkSummary(true));
        when(measurableRatingDao.saveRatingItem(appRef, 10L, "G", "tester")).thenReturn(true);

        service.saveRatingItem(appRef, 10L, "G", "tester");

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService, times(2)).write(captor.capture());

        assertEquals(Operation.UPDATE, captor.getAllValues().get(0).operation());
        assertTrue(captor.getAllValues().get(0).message().endsWith("old value: Bad [B]"));
    }


    @Test
    void saveRatingItemDoesNotAuditAFailedSave() {
        when(measurableDao.getById(10L)).thenReturn(mkMeasurable(10L, 100L));
        when(ratingSchemeService.findRatingSchemeItemsForEntityAndCategory(appRef, 100L))
                .thenReturn(emptyList());
        when(measurableRatingDao.resolveLoggingContextForRatingChange(appRef, 10L, "G"))
                .thenReturn(mkSummary(false));
        when(measurableRatingDao.saveRatingItem(appRef, 10L, "G", "tester")).thenReturn(false);

        assertFalse(service.saveRatingItem(appRef, 10L, "G", "tester"));

        verifyNoInteractions(changeLogService);
    }


    @Test
    void saveRatingIsPrimaryAndDescriptionAuditTheirChanges() {
        when(measurableRatingDao.resolveLoggingContextForRatingChange(appRef, 10L, null))
                .thenReturn(mkSummary(true));
        when(measurableRatingDao.saveRatingIsPrimary(appRef, 10L, true, "tester")).thenReturn(true);
        when(measurableRatingDao.saveRatingDescription(appRef, 10L, "notes", "tester")).thenReturn(true);

        assertTrue(service.saveRatingIsPrimary(appRef, 10L, true, "tester"));
        assertTrue(service.saveRatingDescription(appRef, 10L, "notes", "tester"));

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService, times(4)).write(captor.capture());

        assertEquals(
                "Setting primary rating flag for: Trading [10], in category: Capability [100]",
                captor.getAllValues().get(0).message());
        assertEquals(
                "Updating description for rating: Trading [10], in category: Capability [100], new value: notes",
                captor.getAllValues().get(2).message());
    }


    @Test
    void migrateRatingsResolvesTheRatingSchemeFromTheCategory() {
        when(measurableCategoryDao.getById(100L)).thenReturn(mkCategory(100L, 55L));
        List<RatingSchemeItem> items = singletonList(ImmutableRatingSchemeItem
                .builder()
                .id(1L)
                .name("Good")
                .description("d")
                .ratingSchemeId(55L)
                .rating("G")
                .color("green")
                .position(1)
                .build());
        when(ratingSchemeDAO.fetchItems(any())).thenReturn(items);

        service.migrateRatings(10L, 20L, "tester", 100L);

        verify(measurableRatingDao).migrateRatings(10L, 20L, "tester", 55L, items);
    }


    @Test
    void migrateRatingsRefusesToRunAgainstAnUnknownCategory() {
        when(measurableCategoryDao.getById(100L)).thenReturn(null);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.migrateRatings(10L, 20L, "tester", 100L));
    }
}
