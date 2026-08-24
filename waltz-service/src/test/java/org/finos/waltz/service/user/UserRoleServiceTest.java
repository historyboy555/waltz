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

package org.finos.waltz.service.user;

import org.finos.waltz.data.person.PersonDao;
import org.finos.waltz.data.role.RoleDao;
import org.finos.waltz.data.user.UserRoleDao;
import org.finos.waltz.model.bulk_upload.BulkUploadMode;
import org.finos.waltz.model.changelog.ChangeLog;
import org.finos.waltz.model.person.Person;
import org.finos.waltz.model.role.ImmutableRole;
import org.finos.waltz.model.user.*;
import org.finos.waltz.service.changelog.ChangeLogService;
import org.finos.waltz.service.person.PersonService;
import org.finos.waltz.service.settings.SettingsService;
import org.jooq.lambda.tuple.Tuple2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.finos.waltz.common.SetUtilities.asSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserRoleServiceTest {

    @Mock
    private UserRoleDao userRoleDao;
    @Mock
    private RoleDao roleDao;
    @Mock
    private PersonDao personDao;
    @Mock
    private ChangeLogService changeLogService;
    @Mock
    private PersonService personService;
    @Mock
    private SettingsService settingsService;

    private UserRoleService service;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(settingsService.getValue(anyString())).thenReturn(Optional.empty());
        service = new UserRoleService(
                userRoleDao,
                roleDao,
                personDao,
                changeLogService,
                personService,
                settingsService);
    }

    @Test
    void hasRoleAndHasAnyRolePinEmptyRequiredRoles() {
        when(userRoleDao.getUserRoles("user")).thenReturn(asSet("admin"));

        assertTrue(service.hasRole("user", asSet()));
        assertTrue(service.hasRole("user", "admin"));
        assertFalse(service.hasAnyRole("user", asSet()));
        assertTrue(service.hasAnyRole("user", asSet("admin", "other")));
    }

    @Test
    void getByUserIdBuildsUserWithRequestedNameAndRoles() {
        when(userRoleDao.getUserRoles("user")).thenReturn(asSet("admin", "reader"));

        User result = service.getByUserId("user");

        assertEquals("user", result.userName());
        assertEquals(asSet("admin", "reader"), result.roles());
    }

    @Test
    void updateRolesForUnknownPersonDoesNotWriteChangelogAndStillUpdatesDao() {
        UpdateRolesCommand command = ImmutableUpdateRolesCommand.builder()
                .roles(asSet("admin"))
                .comment("comment")
                .build();
        when(personService.getPersonByUserId("missing")).thenReturn(null);
        when(userRoleDao.updateRoles("missing", command.roles())).thenReturn(3);

        assertEquals(3, service.updateRoles("actor", "missing", command));

        verify(personService).getPersonByUserId("missing");
        verifyNoInteractions(changeLogService);
        verify(userRoleDao).updateRoles("missing", command.roles());
        // The self-role-management guard is skipped when the target person is unknown.
        verifyNoInteractions(settingsService);
    }

    @Test
    void updateRolesRejectsSelfManagementWhenDisabledAndLeavesDaoUntouched() {
        Person person = mock(Person.class);
        when(person.id()).thenReturn(Optional.of(9L));
        when(personService.getPersonByUserId("actor")).thenReturn(person);
        when(settingsService.getValue(anyString())).thenReturn(Optional.of("true"));
        UpdateRolesCommand command = ImmutableUpdateRolesCommand.builder()
                .roles(asSet("admin"))
                .comment("comment")
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateRoles("actor", "actor", command));

        verifyNoInteractions(userRoleDao, changeLogService);
    }

    @Test
    void updateRolesWritesSortedRolesAndNoneCommentFallback() {
        Person person = mock(Person.class);
        when(person.id()).thenReturn(Optional.of(9L));
        when(personService.getPersonByUserId("target")).thenReturn(person);
        UpdateRolesCommand command = ImmutableUpdateRolesCommand.builder()
                .roles(asSet("zeta", "alpha"))
                .build();
        when(userRoleDao.updateRoles("target", command.roles())).thenReturn(1);

        assertEquals(1, service.updateRoles("actor", "target", command));

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService).write(captor.capture());
        ChangeLog log = captor.getValue();
        assertEquals("Roles for target updated to [alpha, zeta].  Comment: none", log.message());
        assertEquals("actor", log.userId());
        assertEquals(Optional.empty(), log.childKind());
        assertEquals(Optional.empty(), log.childId());
    }

    @Test
    void bulkUploadPreviewEmptyInputReturnsEmptyWithoutCollaborators() {
        assertEquals(List.of(), service.bulkUploadPreview(BulkUploadMode.ADD_ONLY, List.of(), "actor"));

        verifyNoInteractions(personDao, roleDao, settingsService);
    }

    @Test
    void bulkUploadPreviewStripsHeadersAndBlanksAndResolvesDelimitedRows() {
        when(personDao.findAllEmails()).thenReturn(List.of("target", "actor"));
        when(roleDao.findAllRoles()).thenReturn(Set.of(
                ImmutableRole.builder()
                        .key("admin")
                        .name("Admin")
                        .description("Admin")
                        .isCustom(false)
                        .build()));

        List<BulkUserOperationRowPreview> previews = service.bulkUploadPreview(
                BulkUploadMode.ADD_ONLY,
                List.of(
                        "username,role,comment",
                        "",
                        "target\tadmin\ttab comment",
                        "unknown,admin,unknown user",
                        "target,missing,unknown role",
                        "actor,admin,self"),
                "actor");

        assertEquals(4, previews.size());
        assertEquals("target", previews.get(0).resolvedUser());
        assertEquals("admin", previews.get(0).resolvedRole());
        assertEquals("tab comment", previews.get(0).resolvedComment());
        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.OK, previews.get(0).status());
        assertNull(previews.get(1).resolvedUser());
        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.ERROR, previews.get(1).status());
        assertEquals("target", previews.get(2).resolvedUser());
        assertNull(previews.get(2).resolvedRole());
        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.ERROR, previews.get(2).status());
        assertEquals("actor", previews.get(3).resolvedUser());
    }

    @Test
    void bulkUploadPreviewTreatsMissingCommentAsAnError() {
        when(personDao.findAllEmails()).thenReturn(List.of("target"));
        when(roleDao.findAllRoles()).thenReturn(Set.of(
                ImmutableRole.builder()
                        .key("admin")
                        .name("Admin")
                        .description("Admin")
                        .isCustom(false)
                        .build()));

        BulkUserOperationRowPreview preview = service.bulkUploadPreview(
                BulkUploadMode.ADD_ONLY,
                List.of("target,admin"),
                "actor").get(0);

        // A row with valid user and role still fails resolution when its comment is empty.
        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.ERROR, preview.status());
    }

    @Test
    void bulkUploadNullsSelfRowsWhenSelfManagementIsDisabled() {
        when(settingsService.getValue(anyString())).thenReturn(Optional.of("true"));
        when(personDao.findAllEmails()).thenReturn(List.of("actor", "target"));
        when(roleDao.findAllRoles()).thenReturn(Set.of(
                ImmutableRole.builder()
                        .key("admin")
                        .name("Admin")
                        .description("Admin")
                        .isCustom(false)
                        .build()));

        List<BulkUserOperationRowPreview> previews = service.bulkUploadPreview(
                BulkUploadMode.ADD_ONLY,
                List.of("actor,admin,self", "target,admin,target"),
                "actor");

        assertNull(previews.get(0).resolvedUser());
        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.ERROR, previews.get(0).status());
        assertEquals("target", previews.get(1).resolvedUser());
        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.OK, previews.get(1).status());
    }

    @Test
    void bulkUploadWritesChangelogBeforeDispatchingEachMode() {
        when(personDao.findAllEmails()).thenReturn(List.of("target"));
        when(roleDao.findAllRoles()).thenReturn(Set.of(
                ImmutableRole.builder()
                        .key("admin")
                        .name("Admin")
                        .description("Admin")
                        .isCustom(false)
                        .build()));
        Person person = mock(Person.class);
        when(person.id()).thenReturn(Optional.of(9L));
        when(personService.getPersonByUserId("target")).thenReturn(person);
        List<String> lines = List.of("target,admin,comment");

        when(userRoleDao.addRoles(anySet())).thenReturn(1);
        when(userRoleDao.removeRoles(anySet())).thenReturn(2);
        when(userRoleDao.replaceRoles(anySet())).thenReturn(3);

        InOrder inOrder = inOrder(changeLogService, userRoleDao);
        assertEquals(1, service.bulkUpload(BulkUploadMode.ADD_ONLY, lines, "actor"));
        inOrder.verify(changeLogService).write(anySet());
        inOrder.verify(userRoleDao).addRoles(anySet());
        assertEquals(2, service.bulkUpload(BulkUploadMode.REMOVE_ONLY, lines, "actor"));
        inOrder.verify(changeLogService).write(anySet());
        inOrder.verify(userRoleDao).removeRoles(anySet());
        assertEquals(3, service.bulkUpload(BulkUploadMode.REPLACE, lines, "actor"));
        inOrder.verify(changeLogService).write(anySet());
        inOrder.verify(userRoleDao).replaceRoles(anySet());

        verify(userRoleDao).addRoles(eq(asSet(org.jooq.lambda.tuple.Tuple.tuple("target", "admin"))));
    }
}
