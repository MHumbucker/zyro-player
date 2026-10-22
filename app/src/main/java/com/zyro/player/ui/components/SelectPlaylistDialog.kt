package com.zyro.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zyro.player.data.local.PlaylistEntity
import com.zyro.player.data.remote.TrackInfo

@Composable
fun SelectPlaylistDialog(
    track: TrackInfo,
    playlists: List<PlaylistEntity>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (PlaylistEntity) -> Unit,
    serverPlaylists: List<com.zyro.player.data.remote.PlaylistInfo> = emptyList(),
    onSelectServerPlaylist: (Long) -> Unit = {},
    isSubmitting: Boolean = false
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(ZyroShapes.XXL))
                .background(ZyroDialogBackground)
                .zyroPlasticGlass(
                    shape = RoundedCornerShape(ZyroShapes.XXL),
                    strong = true
                )
                .padding(24.dp)
        ) {
            Column {
                Text(
                    text = "Добавить в плейлист",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ZyroTextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = track.title,
                    fontSize = 14.sp,
                    color = ZyroTextSecondary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (isSubmitting) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = ZyroAccent,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Добавляем в плейлист…",
                                fontSize = 13.sp,
                                color = ZyroTextSecondary
                            )
                        }
                    }
                } else if (playlists.isEmpty() && serverPlaylists.isEmpty()) {
                    Text(
                        text = "У вас еще нет созданных плейлистов.\nПерейдите в библиотеку, чтобы создать плейлист.",
                        fontSize = 14.sp,
                        color = ZyroTextSecondary,
                        lineHeight = 20.sp
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Серверные плейлисты SoundCloud
                        items(serverPlaylists, key = { "server_${it.id}" }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ZyroSurface.copy(alpha = 0.06f))
                                    .clickable { onSelectServerPlaylist(playlist.id) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = ZyroAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = ZyroTextPrimary
                                    )
                                    Text(
                                        text = "SoundCloud • ${playlist.trackCount} треков",
                                        fontSize = 12.sp,
                                        color = ZyroTextSecondary
                                    )
                                }
                            }
                        }
                        // Локальные плейлисты
                        items(playlists, key = { it.playlistId }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(ZyroTextPrimary.copy(alpha = GlassAlphaMedium))
                                    .border(
                                        1.dp,
                                        ZyroTextPrimary.copy(alpha = BorderAlpha),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable { onSelectPlaylist(playlist) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = ZyroAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = playlist.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ZyroTextPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                        Text(
                            "Отмена",
                            color = ZyroTextSecondary,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}
