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
import org.finos.waltz.model.EntityKind;
import org.finos.waltz.model.bulk_upload.BulkUploadMode;
import org.finos.waltz.model.changelog.ChangeLog;
import org.finos.waltz.model.person.ImmutablePerson;
import org.finos.waltz.model.person.Person;
import org.finos.waltz.model.person.PersonKind;
import org.finos.waltz.model.role.ImmutableRole;
import org.finos.waltz.model.role.Role;
import org.finos.waltz.model.user.BulkUserOperationRowPreview;
import org.finos.waltz.model.user.ImmutableUpdateRolesCommand;
import org.finos.waltz.model.user.SystemRole;
import org.finos.waltz.model.user.UpdateRolesCommand;
import org.finos.waltz.model.user.User;
import org.finos.waltz.service.changelog.ChangeLogService;
import org.finos.waltz.service.person.PersonService;
import org.finos.waltz.service.settings.SettingsService;
import org.jooq.lambda.tuple.Tuple2;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.finos.waltz.common.SetUtilities.asSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization tests for {@link UserRoleService} - the highest fan-in
 * service in Waltz (it backs virtually every authorisation check in
 * waltz-web).  These tests capture the current semantics of the role checks,
 * the audit logging around role updates and the bulk upload preview /
 * resolution rules, including several behaviours which look questionable
 * (flagged inline with SUSPECTED BUG).
 */
class UserRoleServiceTest {

    private final UserRoleDao userRoleDao = mock(UserRoleDao.class);
    private final RoleDao roleDao = mock(RoleDao.class);
    private final PersonDao personDao = mock(PersonDao.class);
    private final ChangeLogService changeLogService = mock(ChangeLogService.class);
    private final PersonService personService = mock(PersonService.class);
    private final SettingsService settingsService = mock(SettingsService.class);

    private final UserRoleService service = new UserRoleService(
            userRoleDao,
            roleDao,
            personDao,
            changeLogService,
            personService,
            settingsService);


    private static Person mkPerson(long id, String email) {
        return ImmutablePerson
                .builder()
                .id(id)
                .employeeId("emp-" + id)
                .displayName("person-" + id)
                .email(email)
                .isRemoved(false)
                .personKind(PersonKind.EMPLOYEE)
                .build();
    }


    private static Role mkRole(String key) {
        return ImmutableRole
                .builder()
                .key(key)
                .name(key)
                .description(key)
                .isCustom(false)
                .build();
    }


    private static UpdateRolesCommand mkUpdateCmd(String comment, String... roles) {
        return ImmutableUpdateRolesCommand
                .builder()
                .roles(asSet(roles))
                .comment(comment)
                .build();
    }


    private void selfRoleMgmtDisabled(boolean disabled) {
        when(settingsService.getValue("feature.user-roles.disable-self-role-mgmt"))
                .thenReturn(Optional.of(Boolean.toString(disabled)));
    }


    @Test
    void constructionRejectsNullCollaborators() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UserRoleService(null, roleDao, personDao, changeLogService, personService, settingsService));
    }


    @Test
    void hasRoleRequiresEveryGivenRoleAndIsVacuouslyTrueForNoRoles() {
        when(userRoleDao.getUserRoles("alice")).thenReturn(asSet("ADMIN", "BETA_TESTER"));

        assertTrue(service.hasRole("alice", "ADMIN"));
        assertTrue(service.hasRole("alice", "ADMIN", "BETA_TESTER"));
        assertFalse(service.hasRole("alice", "ADMIN", "TAXONOMY_EDITOR"));

        // SUSPECTED BUG (characterized, not fixed): asking whether a user has
        // *no* roles returns true, so a caller which accidentally passes an
        // empty role set will grant access to everybody.
        assertTrue(service.hasRole("alice", emptySet()));
    }


    @Test
    void hasAnyRoleIsTrueWhenAtLeastOneRoleIntersects() {
        when(userRoleDao.getUserRoles("alice")).thenReturn(asSet("BETA_TESTER"));

        assertTrue(service.hasAnyRole("alice", asSet("ADMIN", "BETA_TESTER")));
        assertFalse(service.hasAnyRole("alice", asSet("ADMIN")));
        assertFalse(service.hasAnyRole("alice", emptySet()), "empty required roles cannot intersect");
    }


    @Test
    void systemRoleOverloadsAreResolvedByEnumName() {
        when(userRoleDao.getUserRoles("alice")).thenReturn(asSet(SystemRole.ADMIN.name()));

        assertTrue(service.hasRole("alice", SystemRole.ADMIN));
        assertTrue(service.hasAnyRole("alice", SystemRole.ADMIN, SystemRole.BETA_TESTER));
        assertFalse(service.hasRole("alice", SystemRole.ADMIN, SystemRole.BETA_TESTER));
    }


    @Test
    void getByUserIdAlwaysReturnsAUserEvenIfThatUserDoesNotExist() {
        when(userRoleDao.getUserRoles("nobody")).thenReturn(emptySet());

        User user = service.getByUserId("nobody");

        assertEquals("nobody", user.userName());
        assertTrue(user.roles().isEmpty(), "an unknown user is indistinguishable from a user with no roles");
    }


    @Test
    void updateRolesWritesAnAuditEntryAgainstTheTargetPerson() {
        selfRoleMgmtDisabled(false);
        when(personService.getPersonByUserId("bob")).thenReturn(mkPerson(22L, "bob"));
        when(userRoleDao.updateRoles("bob", asSet("ADMIN"))).thenReturn(1);

        assertEquals(1, service.updateRoles("alice", "bob", mkUpdateCmd("because", "ADMIN")));

        ArgumentCaptor<ChangeLog> captor = ArgumentCaptor.forClass(ChangeLog.class);
        verify(changeLogService).write(captor.capture());
        ChangeLog entry = captor.getValue();

        assertEquals(EntityKind.PERSON, entry.parentReference().kind());
        assertEquals(22L, entry.parentReference().id());
        assertEquals("alice", entry.userId(), "the acting user is recorded, not the target");
        assertEquals("Roles for bob updated to [ADMIN].  Comment: because", entry.message());
    }


    @Test
    void updateRolesStillUpdatesRolesWhenTheTargetHasNoPersonRecord() {
        when(personService.getPersonByUserId("ghost")).thenReturn(null);
        when(userRoleDao.updateRoles("ghost", asSet("ADMIN"))).thenReturn(1);

        assertEquals(1, service.updateRoles("alice", "ghost", mkUpdateCmd("c", "ADMIN")));

        verify(changeLogService, never()).write(any(ChangeLog.class));
    }


    @Test
    void selfRoleManagementIsBlockedOnlyWhenTheTargetHasAPersonRecord() {
        selfRoleMgmtDisabled(true);
        when(personService.getPersonByUserId("alice")).thenReturn(mkPerson(1L, "alice"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateRoles("alice", "alice", mkUpdateCmd("c", "ADMIN")));
        assertEquals("Cannot modify own roles.", ex.getMessage());
        verify(userRoleDao, never()).updateRoles(anyString(), anySet());

        // SUSPECTED BUG (characterized, not fixed): the self-role-management
        // guard sits inside the `person != null` branch, so a user without a
        // person record can grant roles to themselves even when the
        // feature.user-roles.disable-self-role-mgmt setting is enabled.
        when(personService.getPersonByUserId("ghost")).thenReturn(null);
        when(userRoleDao.updateRoles("ghost", asSet("ADMIN"))).thenReturn(1);

        assertEquals(1, service.updateRoles("ghost", "ghost", mkUpdateCmd("c", "ADMIN")));
    }


    @Test
    void bulkUploadPreviewAndBulkUploadShortCircuitForEmptyInput() {
        assertEquals(emptyList(), service.bulkUploadPreview(BulkUploadMode.ADD_ONLY, emptyList(), "alice"));
        assertEquals(emptyList(), service.bulkUploadPreview(BulkUploadMode.ADD_ONLY, null, "alice"));
        assertEquals(0, service.bulkUpload(BulkUploadMode.ADD_ONLY, emptyList(), "alice"));

        verify(changeLogService, never()).write(anySet());
    }


    @Test
    void bulkUploadPreviewResolvesUsersAndRolesAndDropsHeaderAndBlankLines() {
        selfRoleMgmtDisabled(false);
        when(personDao.findAllEmails()).thenReturn(asList("bob", "carol"));
        when(roleDao.findAllRoles()).thenReturn(asSet(mkRole("ADMIN")));

        List<BulkUserOperationRowPreview> previews = service.bulkUploadPreview(
                BulkUploadMode.ADD_ONLY,
                asList(
                        "username,role,comment",   // header - dropped
                        "",                        // blank - dropped
                        "bob,ADMIN,ok comment",
                        "carol\tADMIN\ttab delimited works",
                        "dave,ADMIN,unknown person",
                        "bob,NOT_A_ROLE,unknown role",
                        "bob,ADMIN"),              // missing comment
                "alice");

        assertEquals(5, previews.size());

        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.OK, previews.get(0).status());
        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.OK, previews.get(1).status());
        assertEquals("carol", previews.get(1).resolvedUser());

        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.ERROR, previews.get(2).status());
        assertNull(previews.get(2).resolvedUser(), "unknown people do not resolve");
        assertEquals("dave", previews.get(2).givenUser(), "but the given value is echoed back");

        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.ERROR, previews.get(3).status());
        assertNull(previews.get(3).resolvedRole(), "unknown roles do not resolve");

        // SUSPECTED BUG (characterized, not fixed): a row with a valid user and
        // role but no comment is reported as ERROR (and silently skipped by
        // bulkUpload) purely because the comment is empty.
        assertEquals(BulkUserOperationRowPreview.ResolutionStatus.ERROR, previews.get(4).status());
        assertEquals("bob", previews.get(4).resolvedUser());
        assertEquals("ADMIN", previews.get(4).resolvedRole());
    }


    @Test
    void bulkUploadPreviewRefusesToResolveTheCallersOwnRowWhenSelfRoleMgmtIsDisabled() {
        selfRoleMgmtDisabled(true);
        when(personDao.findAllEmails()).thenReturn(asList("alice", "bob"));
        when(roleDao.findAllRoles()).thenReturn(asSet(mkRole("ADMIN")));

        List<BulkUserOperationRowPreview> previews = service.bulkUploadPreview(
                BulkUploadMode.ADD_ONLY,
                asList("alice,ADMIN,mine", "bob,ADMIN,theirs"),
                "alice");

        assertNull(previews.get(0).resolvedUser());
        assertEquals("bob", previews.get(1).resolvedUser());
    }


    @Test
    void bulkUploadOnlyAppliesResolvedRowsAndDispatchesOnMode() {
        selfRoleMgmtDisabled(false);
        when(personDao.findAllEmails()).thenReturn(asList("bob"));
        when(roleDao.findAllRoles()).thenReturn(asSet(mkRole("ADMIN")));
        when(personService.getPersonByUserId("bob")).thenReturn(mkPerson(22L, "bob"));
        when(userRoleDao.addRoles(anySet())).thenReturn(1);
        when(userRoleDao.removeRoles(anySet())).thenReturn(2);
        when(userRoleDao.replaceRoles(anySet())).thenReturn(3);

        List<String> lines = asList("bob,ADMIN,ok", "dave,ADMIN,unknown");

        assertEquals(1, service.bulkUpload(BulkUploadMode.ADD_ONLY, lines, "alice"));
        assertEquals(2, service.bulkUpload(BulkUploadMode.REMOVE_ONLY, lines, "alice"));
        assertEquals(3, service.bulkUpload(BulkUploadMode.REPLACE, lines, "alice"));

        ArgumentCaptor<Set<Tuple2<String, String>>> captor = ArgumentCaptor.forClass(Set.class);
        verify(userRoleDao).addRoles(captor.capture());
        assertEquals(1, captor.getValue().size(), "only the resolved row is applied");
    }


    @Test
    void bulkUploadWritesAuditEntriesBeforeApplyingTheChange() {
        selfRoleMgmtDisabled(false);
        when(personDao.findAllEmails()).thenReturn(asList("bob"));
        when(roleDao.findAllRoles()).thenReturn(asSet(mkRole("ADMIN")));
        when(personService.getPersonByUserId("bob")).thenReturn(mkPerson(22L, "bob"));
        when(userRoleDao.addRoles(anySet())).thenReturn(1);

        service.bulkUpload(BulkUploadMode.ADD_ONLY, singletonList("bob,ADMIN,ok"), "alice");

        ArgumentCaptor<Collection<ChangeLog>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(changeLogService).write(captor.capture());
        ChangeLog entry = captor.getValue().iterator().next();

        assertEquals("Role for bob updated to ADMIN.  Comment: ok", entry.message());
        assertEquals("alice", entry.userId());
    }


    @Test
    void bulkUploadBlowsUpIfAResolvedEmailHasNoCorrespondingPerson() {
        // SUSPECTED BUG (characterized, not fixed): rows are resolved against
        // personDao.findAllEmails() but the audit log looks the person up again
        // via personService.getPersonByUserId(..) and dereferences
        // person.id().get() with no null / empty check, so a mismatch between
        // the two lookups results in a NullPointerException *after* nothing has
        // been written.
        selfRoleMgmtDisabled(false);
        when(personDao.findAllEmails()).thenReturn(asList("bob"));
        when(roleDao.findAllRoles()).thenReturn(asSet(mkRole("ADMIN")));
        when(personService.getPersonByUserId("bob")).thenReturn(null);

        assertThrows(
                NullPointerException.class,
                () -> service.bulkUpload(BulkUploadMode.ADD_ONLY, singletonList("bob,ADMIN,ok"), "alice"));

        verify(userRoleDao, never()).addRoles(anySet());
    }


    @Test
    void simpleUserLookupsAreDirectDelegations() {
        when(userRoleDao.findAllUsers()).thenReturn(emptySet());
        when(userRoleDao.findUsersForRole(1L)).thenReturn(emptySet());
        when(userRoleDao.getUserRoles("alice")).thenReturn(asSet("ADMIN"));

        assertTrue(service.findAllUsers().isEmpty());
        assertTrue(service.findUsersForRole(1L).isEmpty());
        assertEquals(asSet("ADMIN"), service.getUserRoles("alice"));
    }
}
