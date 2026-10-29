package com.tanasi.streamflix.ui.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tanasi.streamflix.data.model.EpisodeOption

/** Respaldo explícito: no fabrica listas ni cambia de capítulo por su cuenta. */
@Composable
fun ManualEpisodePicker(selected: EpisodeOption?, onSelect: (EpisodeOption) -> Unit) {
    var season by remember { mutableStateOf((selected?.season ?: 1).toString()) }
    var episode by remember { mutableStateOf((selected?.episode ?: 1).toString()) }
    val seasonNumber = season.toIntOrNull()
    val episodeNumber = episode.toIntOrNull()
    val valid = seasonNumber != null && seasonNumber in 0..100 && episodeNumber != null && episodeNumber in 1..10000
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = season,
                onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) season = it },
                label = { Text("Temporada") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = episode,
                onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) episode = it },
                label = { Text("Episodio") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(enabled = valid, onClick = { onSelect(EpisodeOption(seasonNumber!!, episodeNumber!!)) }) {
            Text("Buscar este episodio")
        }
    }
}
