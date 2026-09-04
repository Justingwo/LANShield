package org.distrinet.lanshield

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalNetworkPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `not required below API 37`() {
        assertThat(LocalNetworkPermission.isRequired(sdkInt = 36)).isFalse()
    }

    @Test
    fun `required from API 37`() {
        assertThat(LocalNetworkPermission.isRequired(sdkInt = 37)).isTrue()
        assertThat(LocalNetworkPermission.isRequired(sdkInt = 38)).isTrue()
    }

    @Test
    fun `granted implicitly when not required, even without the runtime grant`() {
        assertThat(LocalNetworkPermission.isGranted(context, sdkInt = 36)).isTrue()
    }

    @Test
    fun `not granted when required and the runtime grant is missing`() {
        assertThat(LocalNetworkPermission.isGranted(context, sdkInt = 37)).isFalse()
    }

    @Test
    fun `granted when required and the runtime grant is present`() {
        shadowOf(context as Application).grantPermissions(LocalNetworkPermission.PERMISSION)
        assertThat(LocalNetworkPermission.isGranted(context, sdkInt = 37)).isTrue()
    }
}
