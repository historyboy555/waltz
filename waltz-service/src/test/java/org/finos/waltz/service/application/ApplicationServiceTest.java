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

package org.finos.waltz.service.application;

import org.finos.waltz.data.application.ApplicationDao;
import org.finos.waltz.data.application.search.ApplicationSearchDao;
import org.finos.waltz.data.entity_alias.EntityAliasDao;
import org.finos.waltz.model.Criticality;
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.EntityReference;
import org.finos.waltz.model.application.AppRegistrationRequest;
import org.finos.waltz.model.application.AppRegistrationResponse;
import org.finos.waltz.model.application.Application;
import org.finos.waltz.model.application.ApplicationKind;
import org.finos.waltz.model.application.AssetCodeRelationshipKind;
import org.finos.waltz.model.application.ImmutableAppRegistrationRequest;
import org.finos.waltz.model.application.ImmutableAppRegistrationResponse;
import org.finos.waltz.model.application.ImmutableApplication;
import org.finos.waltz.model.application.LifecyclePhase;
import org.finos.waltz.model.entity_search.EntitySearchOptions;
import org.finos.waltz.model.external_identifier.ExternalIdValue;
import org.finos.waltz.model.rating.RagRating;
import org.finos.waltz.service.tag.TagService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.finos.waltz.common.SetUtilities.asSet;
import static org.finos.waltz.model.EntityReference.mkRef;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link ApplicationService}.  Most methods are
 * simple delegations; the interesting behaviour is the app registration
 * side-effects and the asset-code based relationship classification in
 * {@code findRelated}.
 */
class ApplicationServiceTest {

    private final ApplicationDao applicationDao = mock(ApplicationDao.class);
    private final TagService tagService = mock(TagService.class);
    private final EntityAliasDao entityAliasDao = mock(EntityAliasDao.class);
    private final ApplicationSearchDao appSearchDao = mock(ApplicationSearchDao.class);

    private final ApplicationService service = new ApplicationService(
            applicationDao,
            tagService,
            entityAliasDao,
            appSearchDao);


    private static Application mkApp(long id, String assetCode, String parentAssetCode) {
        return ImmutableApplication
                .builder()
                .id(id)
                .name("app-" + id)
                .description("d")
                .organisationalUnitId(1L)
                .applicationKind(ApplicationKind.IN_HOUSE)
                .lifecyclePhase(LifecyclePhase.PRODUCTION)
                .overallRating(RagRating.G)
                .assetCode(Optional.ofNullable(assetCode).map(ExternalIdValue::of))
                .parentAssetCode(Optional.ofNullable(parentAssetCode).map(ExternalIdValue::of))
                .build();
    }


    private static AppRegistrationRequest mkRegistrationRequest() {
        return ImmutableAppRegistrationRequest
                .builder()
                .name("new app")
                .description("d")
                .organisationalUnitId(1L)
                .applicationKind(ApplicationKind.IN_HOUSE)
                .lifecyclePhase(LifecyclePhase.PRODUCTION)
                .overallRating(RagRating.G)
                .businessCriticality(Criticality.MEDIUM)
                .aliases(asSet("alias-a"))
                .tags(asSet("tag-a"))
                .build();
    }


    @Test
    void constructionRejectsNullCollaborators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApplicationService(null, tagService, entityAliasDao, appSearchDao));
    }


    @Test
    void searchWithAnEmptyQueryDoesNotHitTheSearchDao() {
        assertEquals(emptyList(), service.search(""));
        assertEquals(emptyList(), service.search((String) null));

        verifyNoInteractions(appSearchDao);
    }


    @Test
    void searchWithAQueryBuildsDefaultApplicationSearchOptions() {
        when(appSearchDao.search(any(EntitySearchOptions.class))).thenReturn(emptyList());

        service.search("abc");

        ArgumentCaptor<EntitySearchOptions> captor = ArgumentCaptor.forClass(EntitySearchOptions.class);
        verify(appSearchDao).search(captor.capture());

        assertEquals("abc", captor.getValue().searchQuery());
        assertEquals(singletonList(EntityKind.APPLICATION), captor.getValue().entityKinds());
    }


    @Test
    void simpleLookupsAreDelegatedToTheDao() {
        List<Application> apps = singletonList(mkApp(1L, "a", null));

        when(applicationDao.findAll()).thenReturn(apps);
        when(applicationDao.findByIds(asList(1L, 2L))).thenReturn(apps);
        when(applicationDao.findByAssetCode(ExternalIdValue.of("a"))).thenReturn(apps);
        when(applicationDao.countByOrganisationalUnit()).thenReturn(emptyList());
        when(applicationDao.getById(1L)).thenReturn(mkApp(1L, "a", null));
        when(applicationDao.update(any(Application.class))).thenReturn(1);

        assertSame(apps, service.findAll());
        assertSame(apps, service.findByIds(asList(1L, 2L)));
        assertSame(apps, service.findByAssetCode(ExternalIdValue.of("a")));
        assertTrue(service.countByOrganisationalUnit().isEmpty());
        assertEquals(mkApp(1L, "a", null), service.getById(1L));
        assertEquals(1, service.update(mkApp(1L, "a", null)));
    }


    @Test
    void registeringAnAppAlsoWritesAliasesAndTags() {
        AppRegistrationRequest request = mkRegistrationRequest();
        AppRegistrationResponse response = ImmutableAppRegistrationResponse
                .builder()
                .id(42L)
                .originalRequest(request)
                .build();

        when(applicationDao.registerApp(request)).thenReturn(response);

        assertSame(response, service.registerApp(request, "tester"));

        EntityReference expectedRef = mkRef(EntityKind.APPLICATION, 42L);
        verify(entityAliasDao).updateAliases(expectedRef, asSet("alias-a"));
        verify(tagService).updateTags(expectedRef, asSet("tag-a"), "tester");
    }


    @Test
    void aFailedRegistrationLeavesAliasesAndTagsAlone() {
        AppRegistrationRequest request = mkRegistrationRequest();
        AppRegistrationResponse response = ImmutableAppRegistrationResponse
                .builder()
                .originalRequest(request)
                .message("could not register")
                .build();

        when(applicationDao.registerApp(request)).thenReturn(response);

        assertSame(response, service.registerApp(request, "tester"));

        verify(entityAliasDao, never()).updateAliases(any(EntityReference.class), anySet());
        verify(tagService, never()).updateTags(any(EntityReference.class), anySet(), anyString());
    }


    @Test
    void registeringAnAppWithoutANameIsRejectedBeforeTouchingTheDao() {
        AppRegistrationRequest request = ImmutableAppRegistrationRequest
                .copyOf(mkRegistrationRequest())
                .withName("");

        assertThrows(IllegalArgumentException.class, () -> service.registerApp(request, "tester"));

        verifyNoInteractions(applicationDao);
    }


    @Test
    void findRelatedClassifiesAppsByAssetCodeRelationship() {
        Application app = mkApp(1L, "code-1", "parent-1");
        Application sharing = mkApp(2L, "code-1", "whatever");
        Application parent = mkApp(3L, "parent-1", null);
        Application child = mkApp(4L, "code-4", "code-1");
        Application sibling = mkApp(5L, "code-5", "parent-1");
        Application unrelated = mkApp(6L, "code-6", "parent-6");

        when(applicationDao.findRelatedByApplicationId(1L))
                .thenReturn(asList(app, sharing, parent, child, sibling, unrelated));

        Map<AssetCodeRelationshipKind, List<Application>> related = service.findRelated(1L);

        assertEquals(singletonList(sharing), related.get(AssetCodeRelationshipKind.SHARING));
        assertEquals(singletonList(parent), related.get(AssetCodeRelationshipKind.PARENT));
        assertEquals(singletonList(child), related.get(AssetCodeRelationshipKind.CHILD));
        assertEquals(singletonList(sibling), related.get(AssetCodeRelationshipKind.SIBLING));
        assertEquals(singletonList(unrelated), related.get(AssetCodeRelationshipKind.NONE));
    }


    @Test
    void findRelatedIsEmptyWhenTheDaoResultDoesNotIncludeTheAppItself() {
        // note: the subject app has to be part of the dao result set, otherwise
        // no relationships are reported at all (rather than, say, throwing)
        when(applicationDao.findRelatedByApplicationId(1L))
                .thenReturn(singletonList(mkApp(2L, "code-2", null)));

        assertEquals(emptyMap(), service.findRelated(1L));
    }
}
