package com.example.ui.components

import androidx.compose.runtime.Composable
import com.example.data.model.BusinessEntity
import com.example.ui.DatingViewModel

/** Entry point for business-owner campaign creation and checkout. */
@Composable
fun AdvertiseWithUsScreen(
    viewModel: DatingViewModel,
    business: BusinessEntity?,
    onDismissRequest: () -> Unit
) {
    AdCampaignWizardDialog(viewModel = viewModel, business = business, onDismissRequest = onDismissRequest)
}

@Composable
fun AdvertiseWithUsDialog(onDismissRequest: () -> Unit) {
    AdCampaignWizardDialog(viewModel = null, business = null, onDismissRequest = onDismissRequest)
}
