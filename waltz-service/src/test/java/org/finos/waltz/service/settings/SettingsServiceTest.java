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
import org.finos.waltz.model.settings.Setting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettingsServiceTest {

    @Mock private SettingsDao settingsDao;

    private Setting daoSetting;
    private Setting override;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        daoSetting = setting("key", "from dao");
        override = setting("key", "from override");
    }

    @Test
    void getByNameAndGetValuePreferOverride() {
        when(settingsDao.getByName("key")).thenReturn(daoSetting);
        SettingsService service = new SettingsService(settingsDao, List.of(override));

        assertSame(override, service.getByName("key"));
        assertEquals(Optional.of("from override"), service.getValue("key"));
        // orElse evaluates its DAO argument eagerly, even when an override exists.
        verify(settingsDao, times(2)).getByName("key");
    }

    @Test
    void findAllOnlyOverridesKeysReturnedByDao() {
        Setting other = setting("other", "other value");
        when(settingsDao.findAll()).thenReturn(List.of(daoSetting, other));
        Setting absentFromDao = setting("absent", "override value");
        SettingsService service = new SettingsService(settingsDao, List.of(override, absentFromDao));

        assertEquals(List.of(override, other), service.findAll().stream().toList());
        assertSame(absentFromDao, service.getByName("absent"));
    }

    @Test
    void getValueReturnsEmptyForAbsentOrUnsetValueSettings() {
        when(settingsDao.getByName("absent")).thenReturn(null);
        Setting unsetValue = ImmutableSetting.builder()
                .name("unset-value")
                .description("description")
                .build();
        when(settingsDao.getByName("unset-value")).thenReturn(unsetValue);
        SettingsService service = new SettingsService(settingsDao, List.of());

        assertEquals(Optional.empty(), service.getValue("absent"));
        assertEquals(Optional.empty(), service.getValue("unset-value"));
    }

    @Test
    void nullOverridesCollectionIsTreatedAsEmpty() {
        when(settingsDao.getByName("key")).thenReturn(daoSetting);
        SettingsService service = new SettingsService(settingsDao, null);

        assertSame(daoSetting, service.getByName("key"));
        assertEquals(Optional.of("from dao"), service.getValue("key"));
    }

    private static Setting setting(String name, String value) {
        return ImmutableSetting.builder()
                .name(name)
                .description("description")
                .value(value)
                .build();
    }
}
