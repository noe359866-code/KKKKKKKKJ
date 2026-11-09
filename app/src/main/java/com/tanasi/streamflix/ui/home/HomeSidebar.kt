package com.tanasi.streamflix.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tanasi.streamflix.data.model.Profile
import com.tanasi.streamflix.ui.theme.StreamflixColors
import com.tanasi.streamflix.ui.theme.tvFocusGlow

/** Sidebar de navegación para pantallas de sala, operable enteramente con D-Pad. */
@Composable
internal fun HomeSidebar(
    category: HomeCategory,
    searching: Boolean,
    profile: Profile?,
    horizontalSafeInset: Dp,
    verticalSafeInset: Dp,
    compactHeight: Boolean,
    onCategorySelected: (HomeCategory) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onSwitchProfile: () -> Unit,
    onDismiss: (restoreHomeFocus: Boolean) -> Unit,
) {
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { initialFocus.requestFocus() }
    }

    Dialog(
            onDismissRequest = { onDismiss(true) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                        onDismiss(true)
                        true
                    } else {
                        false
                    }
                }
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(
                    start = horizontalSafeInset,
                    top = verticalSafeInset,
                    bottom = verticalSafeInset,
                ),
        ) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(Color(0xF2141417), RoundedCornerShape(24.dp))
                    .padding(horizontal = if (compactHeight) 18.dp else 22.dp, vertical = if (compactHeight) 12.dp else 24.dp),
            ) {
                Text(
                    text = "STREAMFLIX",
                    style = if (compactHeight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 1.4.sp,
                )
                if (!compactHeight) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Tu entretenimiento, en un solo lugar",
                        style = MaterialTheme.typography.bodySmall,
                        color = StreamflixColors.OnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(if (compactHeight) 14.dp else 30.dp))

                HomeCategory.entries.forEachIndexed { index, item ->
                    SidebarItem(
                        label = item.label,
                        selected = item == category && !searching,
                        compactHeight = compactHeight,
                        focusRequester = if (item == category && !searching) initialFocus else null,
                        onClick = {
                            onCategorySelected(item)
                            onDismiss(true)
                        },
                    )
                    if (index < HomeCategory.entries.lastIndex) Spacer(Modifier.height(if (compactHeight) 2.dp else 4.dp))
                }

                Spacer(Modifier.height(if (compactHeight) 12.dp else 20.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
                )
                Spacer(Modifier.height(if (compactHeight) 10.dp else 16.dp))

                SidebarItem(
                    label = "Buscar",
                    selected = searching,
                    compactHeight = compactHeight,
                    focusRequester = if (searching) initialFocus else null,
                    onClick = {
                        onDismiss(false)
                        onSearch()
                    },
                )

                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.12f)),
                )
                Spacer(Modifier.height(if (compactHeight) 8.dp else 12.dp))

                if (profile != null) {
                    SidebarItem(
                        label = "Perfil · ${profile.name}",
                        selected = false,
                        compactHeight = compactHeight,
                        onClick = {
                            onDismiss(false)
                            onSwitchProfile()
                        },
                    )
                    Spacer(Modifier.height(if (compactHeight) 2.dp else 4.dp))
                }
                SidebarItem(
                    label = "Ajustes",
                    selected = false,
                    compactHeight = compactHeight,
                    onClick = {
                        onDismiss(false)
                        onSettings()
                    },
                )
            }
            Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun SidebarItem(
    label: String,
    selected: Boolean,
    compactHeight: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val background = when {
        focused -> Color.White
        selected -> Color.White.copy(alpha = 0.13f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusGlow(
                progress = if (focused) 1f else 0f,
                cornerRadius = 14.dp,
            )
            .background(background, RoundedCornerShape(14.dp))
            .semantics {
                this.selected = selected
                role = Role.Button
            }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (compactHeight) 7.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(
                    if (selected || focused) {
                        if (focused) Color.Black else Color.White
                    } else {
                        Color.Transparent
                    },
                    RoundedCornerShape(2.dp),
                ),
        )
        SidebarGlyph(label = label, tint = if (focused) Color.Black else Color.White)
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) Color.Black else Color.White,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SidebarGlyph(label: String, tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.7.dp.toPx()
        val center = Offset(w / 2f, h / 2f)

        when {
            label == HomeCategory.Home.label -> {
                val roof = Path().apply {
                    moveTo(w * 0.12f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.48f)
                }
                drawPath(roof, tint, style = Stroke(stroke))
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.25f, h * 0.43f),
                    size = androidx.compose.ui.geometry.Size(w * 0.50f, h * 0.45f),
                    cornerRadius = CornerRadius(w * 0.04f),
                    style = Stroke(stroke),
                )
            }
            label == HomeCategory.Movies.label -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.17f),
                    size = androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.66f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = Stroke(stroke),
                )
                drawLine(tint, Offset(w * 0.31f, h * 0.18f), Offset(w * 0.31f, h * 0.82f), stroke)
                drawLine(tint, Offset(w * 0.69f, h * 0.18f), Offset(w * 0.69f, h * 0.82f), stroke)
                drawLine(tint, Offset(w * 0.32f, h * 0.50f), Offset(w * 0.68f, h * 0.50f), stroke)
            }
            label == HomeCategory.Series.label -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.15f, h * 0.16f),
                    size = androidx.compose.ui.geometry.Size(w * 0.70f, h * 0.68f),
                    cornerRadius = CornerRadius(w * 0.08f),
                    style = Stroke(stroke),
                )
                drawLine(tint, Offset(w * 0.28f, h * 0.40f), Offset(w * 0.72f, h * 0.40f), stroke)
                drawLine(tint, Offset(w * 0.28f, h * 0.61f), Offset(w * 0.72f, h * 0.61f), stroke)
            }
            label == "Buscar" -> {
                drawCircle(tint, radius = w * 0.28f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(stroke))
                drawLine(tint, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.88f, h * 0.88f), stroke)
            }
            label.startsWith("Perfil") -> {
                drawCircle(tint, radius = w * 0.16f, center = Offset(center.x, h * 0.34f), style = Stroke(stroke))
                val shoulders = Path().apply {
                    moveTo(w * 0.18f, h * 0.86f)
                    quadraticTo(w * 0.22f, h * 0.57f, center.x, h * 0.57f)
                    quadraticTo(w * 0.78f, h * 0.57f, w * 0.82f, h * 0.86f)
                }
                drawPath(shoulders, tint, style = Stroke(stroke))
            }
            else -> {
                for (spoke in 0 until 8) {
                    val angle = Math.PI * spoke / 4.0
                    drawLine(
                        tint,
                        Offset(center.x + (kotlin.math.cos(angle) * w * 0.29).toFloat(), center.y + (kotlin.math.sin(angle) * h * 0.29).toFloat()),
                        Offset(center.x + (kotlin.math.cos(angle) * w * 0.44).toFloat(), center.y + (kotlin.math.sin(angle) * h * 0.44).toFloat()),
                        stroke,
                    )
                }
                drawCircle(tint, radius = w * 0.29f, center = center, style = Stroke(stroke))
                drawCircle(tint, radius = w * 0.09f, center = center)
            }
        }
    }
}
