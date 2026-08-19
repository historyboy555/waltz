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

package org.finos.waltz.service.settings;

import org.finos.waltz.data.settings.SettingsDao;
import org.finos.waltz.model.settings.ImmutableSetting;
import org.finos.waltz.model.settings.ImmutableUpdateSettingsCommand;
import org.finos.waltz.model.settings.Setting;
import org.finos.waltz.model.settings.UpdateSettingsCommand;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Characterization tests: these record the _current_ behaviour of
 * {@link SettingsService}, including behaviour which may be surprising.
 */
class SettingsServiceTest {

    private final SettingsDao settingsDao = mock(SettingsDao.class);


    private static Setting mkSetting(String name, String value) {
        return ImmutableSetting
                .builder()
                .name(name)
                .value(Optional.ofNullable(value))
                .build();
    }


    @Test
    void getByNamePrefersTheOverrideOverTheStoredValue() {
        SettingsService svc = new SettingsService(
                settingsDao,
                singletonList(mkSetting("a", "override")));

        Setting result = svc.getByName("a");

        assertEquals(Optional.of("override"), result.value());

        // SUSPECTED BUG (characterized, not fixed): getByName uses
        // Optional.orElse(settingsDao.getByName(name)) rather than orElseGet,
        // so the dao (and therefore the database) is queried even when the
        // override already satisfies the lookup.
        verify(settingsDao).getByName("a");
    }


    @Test
    void getByNameFallsBackToTheDaoWhenThereIsNoOverride() {
        Setting stored = mkSetting("b", "stored");
        when(settingsDao.getByName("b")).thenReturn(stored);

        SettingsService svc = new SettingsService(settingsDao, emptyList());

        assertSame(stored, svc.getByName("b"));
    }


    @Test
    void getByNameReturnsNullForAnUnknownSetting() {
        when(settingsDao.getByName("missing")).thenReturn(null);

        SettingsService svc = new SettingsService(settingsDao, emptyList());

        assertNull(svc.getByName("missing"));
    }


    @Test
    void getValueIsEmptyForBothUnknownSettingsAndSettingsWithNoValue() {
        when(settingsDao.getByName("missing")).thenReturn(null);
        when(settingsDao.getByName("valueless")).thenReturn(mkSetting("valueless", null));

        SettingsService svc = new SettingsService(settingsDao, emptyList());

        assertEquals(Optional.empty(), svc.getValue("missing"));
        assertEquals(Optional.empty(), svc.getValue("valueless"));
    }


    @Test
    void findAllSubstitutesOverridesButDoesNotAddOverridesWhichAreNotInTheDatabase() {
        when(settingsDao.findAll()).thenReturn(asList(
                mkSetting("a", "stored-a"),
                mkSetting("b", "stored-b")));

        SettingsService svc = new SettingsService(
                settingsDao,
                asList(
                        mkSetting("a", "override-a"),
                        mkSetting("only-an-override", "never-seen")));

        Collection<Setting> all = svc.findAll();

        assertEquals(2, all.size(), "override-only settings are not added to the result");
        assertTrue(all.contains(mkSetting("a", "override-a")), "stored value for 'a' is replaced by the override");
        assertTrue(all.contains(mkSetting("b", "stored-b")), "settings without an override are returned as-is");
    }


    @Test
    void aNullOverrideCollectionIsTreatedAsNoOverrides() {
        Setting stored = mkSetting("a", "stored");
        when(settingsDao.getByName("a")).thenReturn(stored);

        SettingsService svc = new SettingsService(settingsDao, null);

        assertSame(stored, svc.getByName("a"));
    }


    @Test
    void duplicateOverrideNamesAreSilentlyResolvedInFavourOfTheLastOne() {
        SettingsService svc = new SettingsService(
                settingsDao,
                asList(
                        mkSetting("dupe", "one"),
                        mkSetting("dupe", "two")));

        assertEquals(Optional.of("two"), svc.getValue("dupe"));
    }


    @Test
    void indexByPrefixUpdateAndCreateAreSimpleDelegationsAndIgnoreOverrides() {
        Map<String, String> byPrefix = singletonMap("a.b", "c");
        UpdateSettingsCommand cmd = ImmutableUpdateSettingsCommand
                .builder()
                .name("a")
                .value("v")
                .build();
        Setting toCreate = mkSetting("new", "v");

        when(settingsDao.indexByPrefix("a.")).thenReturn(byPrefix);
        when(settingsDao.update(cmd)).thenReturn(1);
        when(settingsDao.create(toCreate)).thenReturn(1);

        SettingsService svc = new SettingsService(
                settingsDao,
                singletonList(mkSetting("a.b", "override")));

        assertSame(byPrefix, svc.indexByPrefix("a."), "overrides are not applied to prefix lookups");
        assertEquals(1, svc.update(cmd));
        assertEquals(1, svc.create(toCreate));

        verify(settingsDao).update(cmd);
        verify(settingsDao).create(toCreate);
    }
}
