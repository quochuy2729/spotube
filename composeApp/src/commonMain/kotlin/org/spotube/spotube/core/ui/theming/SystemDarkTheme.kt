/*
 * Copyright (C) 2026 Kingkor Roy Tirtho and Spotube Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.spotube.spotube.core.ui.theming

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Whether the OS is in dark mode, observed live; desktop can't rely on Compose's own check under Tao. */
@Composable
expect fun isSystemDarkTheme(): Boolean

/** The OS accent colour (Android 12+ system_accent1_500), or null when the platform has none. */
@Composable
expect fun systemAccentColor(): Color?

/** Whether Modifier.blur really blurs here (Android 12+, desktop, iOS); older Android ignores it. */
expect fun isBlurSupported(): Boolean
