package org.distrinet.lanshield.ui.intro

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import org.distrinet.lanshield.R
import org.distrinet.lanshield.PRIVACY_POLICY_URL
import org.distrinet.lanshield.ui.LANShieldIcons

@Composable
fun ShareLanMetricsSlide(
    page: Int,
    pagerState: PagerState,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean
) {
    val uriHandler = LocalUriHandler.current
    OnboardingSlide(
        page = page,
        pagerState = pagerState,
        title = stringResource(R.string.join_our_academic_study),
        icon = LANShieldIcons.Science,
        body = stringResource(R.string.intro_share_lan_metrics).trimIndent(),
    ) {
        OnboardingTextButton(
            text = stringResource(id = R.string.more_info),
            onClick = { uriHandler.openUri(PRIVACY_POLICY_URL) },
        )
    }
}

@Composable
fun IntroLeftButton(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    slides: List<IntroSlides>,
    coroutineScope: CoroutineScope,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean
) {
    when {
        pagerState.currentSlide(slides) == IntroSlides.JOIN_USER_STUDY -> {
            OnboardingTextButton(
                text = stringResource(R.string.disagree),
                onClick = {
                    doShareLanMetricsDecision(
                        false, onChangeShareLanMetrics, coroutineScope, pagerState, slides
                    )
                },
                modifier = modifier,
            )
        }

        pagerState.currentSlide(slides) == IntroSlides.INTRO_FINISHED && !isShareLanMetricsEnabled -> {
            OnboardingTextButton(
                text = stringResource(R.string.back),
                onClick = {
                    scrollToSlide(pagerState, slides, IntroSlides.JOIN_USER_STUDY, coroutineScope)
                },
                modifier = modifier,
            )
        }

        pagerState.currentPage != 0 -> {
            OnboardingTextButton(
                text = stringResource(R.string.back),
                onClick = { scrollToPreviousPage(pagerState, coroutineScope) },
                modifier = modifier,
            )
        }
    }
}

@Composable
fun IntroRightButton(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    slides: List<IntroSlides>,
    coroutineScope: CoroutineScope,
    onChangeShareLanMetrics: (Boolean) -> Unit,
    isShareLanMetricsEnabled: Boolean,
    onChangeFinishAppIntro: (Boolean) -> Unit,
    navigateToOverview: () -> Unit,
    requestNotificationPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    notificationsEnabled: Boolean,
    localNetworkGranted: Boolean,
) {
    when (pagerState.currentSlide(slides)) {
        IntroSlides.JOIN_USER_STUDY -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.agree),
                onClick = {
                    doShareLanMetricsDecision(
                        true, onChangeShareLanMetrics, coroutineScope, pagerState, slides
                    )
                },
                modifier = modifier,
            )
        }

        IntroSlides.NOTIFICATIONS -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.continue_label),
                onClick = { scrollToNextPage(pagerState, coroutineScope) },
                enabled = notificationsEnabled,
                modifier = modifier,
            )
        }

        IntroSlides.LOCAL_NETWORK -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.continue_label),
                onClick = { scrollToNextPage(pagerState, coroutineScope) },
                enabled = localNetworkGranted,
                modifier = modifier,
            )
        }

        IntroSlides.INTRO_FINISHED -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.finish),
                onClick = {
                    onChangeFinishAppIntro(true)
                    navigateToOverview()
                },
                modifier = modifier,
            )
        }

        else -> {
            OnboardingPrimaryButton(
                text = stringResource(R.string.continue_label),
                onClick = { scrollToNextPage(pagerState, coroutineScope) },
                modifier = modifier,
            )
        }
    }
}
