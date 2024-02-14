/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.gradle

import org.jetbrains.amper.gradle.util.TestBase
import org.jetbrains.amper.gradle.util.doTest
import org.junit.jupiter.api.Test
import kotlin.test.Ignore


@Ignore("requires placing debug models into gradle plugin")
class AndroidArtifactTests : TestBase() {

    @Test
    fun androidArtifactTest() = doTest(Models.singleFragmentAndroidModel)

}
