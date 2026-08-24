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
import org.finos.waltz.model.application.*;
import org.finos.waltz.model.external_identifier.ExternalIdValue;
import org.finos.waltz.service.tag.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationServiceTest {

    @Mock private ApplicationDao applicationDao;
    @Mock private TagService tagService;
    @Mock private EntityAliasDao entityAliasDao;
    @Mock private ApplicationSearchDao applicationSearchDao;

    private ApplicationService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        service = new ApplicationService(applicationDao, tagService, entityAliasDao, applicationSearchDao);
    }

    @Test
    void findRelatedUsesClassificationPrecedenceAndReferenceIdentityForSelfExclusion() {
        Application app = app(1, "code", "parent");
        Application sharing = app(2, "code", "other-parent");
        Application parent = app(3, "parent", "grandparent");
        Application child = app(4, "child", "code");
        Application sibling = app(5, "sibling", "parent");
        Application equalButDistinct = app(1, "code", "parent");
        when(applicationDao.findRelatedByApplicationId(1L))
                .thenReturn(List.of(app, sharing, parent, child, sibling, equalButDistinct));

        Map<AssetCodeRelationshipKind, List<Application>> result = service.findRelated(1L);

        assertEquals(List.of(sharing, equalButDistinct), result.get(AssetCodeRelationshipKind.SHARING));
        assertEquals(List.of(parent), result.get(AssetCodeRelationshipKind.PARENT));
        assertEquals(List.of(child), result.get(AssetCodeRelationshipKind.CHILD));
        assertEquals(List.of(sibling), result.get(AssetCodeRelationshipKind.SIBLING));
    }

    @Test
    void findRelatedReturnsEmptyMapWhenRequestedApplicationIsAbsent() {
        Application other = app(2, "code", "parent");
        when(applicationDao.findRelatedByApplicationId(1L)).thenReturn(List.of(other));

        assertTrue(service.findRelated(1L).isEmpty());
    }

    @Test
    void absentAssetCodesCompareAsSharing() {
        Application app = app(1, null, null);
        Application related = app(2, null, null);
        when(applicationDao.findRelatedByApplicationId(1L)).thenReturn(List.of(app, related));

        Map<AssetCodeRelationshipKind, List<Application>> result = service.findRelated(1L);

        // Empty Optional values compare equal in the current classifier; pin this as-is.
        assertEquals(List.of(related), result.get(AssetCodeRelationshipKind.SHARING));
    }

    @Test
    void emptySearchQueryReturnsEmptyWithoutSearching() {
        assertTrue(service.search("").isEmpty());
        verifyNoInteractions(applicationSearchDao);
    }

    @Test
    void unregisteredResponseLeavesAliasesAndTagsUntouched() {
        AppRegistrationRequest request = mock(AppRegistrationRequest.class);
        AppRegistrationResponse response = mock(AppRegistrationResponse.class);
        when(request.name()).thenReturn("Application");
        when(response.registered()).thenReturn(false);
        when(applicationDao.registerApp(request)).thenReturn(response);

        assertSame(response, service.registerApp(request, "actor"));

        verify(applicationDao).registerApp(request);
        verifyNoInteractions(entityAliasDao, tagService);
    }

    private static Application app(long id, String assetCode, String parentAssetCode) {
        Application app = mock(Application.class);
        when(app.id()).thenReturn(Optional.of(id));
        when(app.assetCode()).thenReturn(Optional.ofNullable(assetCode).map(ExternalIdValue::of));
        when(app.parentAssetCode()).thenReturn(Optional.ofNullable(parentAssetCode).map(ExternalIdValue::of));
        return app;
    }
}
