package dev.wiserlocal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.wiserlocal.app.R

/**
 * Heating history lives in Wiser's cloud, not on the hub, so this tab needs the
 * Wiser account. For now it handles signing in and out; the report comes next.
 */
@Composable
fun InsightsTab(vm: HubViewModel, modifier: Modifier) {
    LaunchedEffect(Unit) { vm.cloudCheck() }
    TabScaffold("Insights", vm, modifier) {
        if (vm.cloudSignedIn) {
            item { AccountCard(vm) }
            item {
                NoteCard(
                    "Signed in. The heat report (heating and hot water on and off, temperatures " +
                        "and set points over time) will appear here next.",
                )
            }
        } else {
            item { SignInCard(vm) }
        }
    }
}

@Composable
private fun SignInCard(vm: HubViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    // Plain remember, so the password is never written into saved instance state.
    var password by remember { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                painterResource(R.drawable.ic_insights),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text("Sign in to your Wiser account", style = MaterialTheme.typography.titleLarge)
            Text(
                "The heat report comes from Wiser's cloud, not the hub, so it needs the account you " +
                    "use in the official app. Your password goes only to Wiser and is not saved on this phone; " +
                    "the app keeps a sign-in token, encrypted, until you sign out.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (!vm.cloudBusy && email.isNotBlank() && password.isNotEmpty()) vm.cloudSignIn(email, password)
                }),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            vm.cloudError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { vm.cloudSignIn(email, password) },
                enabled = !vm.cloudBusy && email.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.cloudBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
                Text("Sign in")
            }
        }
    }
}

@Composable
private fun AccountCard(vm: HubViewModel) {
    val dialogs = LocalDialogs.current
    val account = vm.cloudAccount
    Section("Wiser account") {
        val name = listOfNotNull(account?.firstName, account?.lastName).joinToString(" ")
        InfoRow(name.ifEmpty { "Signed in" }, account?.email)
        account?.hubModel?.let { RowDivider(); InfoRow("Hub model", it) }
        val region = listOfNotNull(account?.countryCode, account?.timeZoneId).joinToString(" · ")
        if (region.isNotEmpty()) { RowDivider(); InfoRow("Region", region) }
        vm.cloudError?.let { RowDivider(); InfoRow("Problem", it) }
        RowDivider()
        Column(Modifier.padding(16.dp)) {
            OutlinedButton(
                onClick = { dialogs.confirm("Sign out of your Wiser account on this phone?", "Sign out") { vm.cloudSignOut() } },
                enabled = !vm.cloudBusy,
            ) { Text("Sign out") }
        }
    }
}
