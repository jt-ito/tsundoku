package suwayomi.tachidesk.manga.impl.util.source

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * A source that is part of a build of the server instead of an extension. The default build has none.
 *
 * A build adds one by listing an implementation in `META-INF/services/suwayomi.tachidesk.manga.impl.util.source.BuiltInSource`
 * (see [java.util.ServiceLoader]); [register] then adds the source like the local source is added, see
 * [GetSource.registerBuiltInSource].
 */
interface BuiltInSource {
    fun register()
}
