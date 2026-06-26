/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.tools

import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "AGWebSearchTool"

// DuckDuckGo instant answers endpoint – no API key required.
private const val DDG_API = "https://api.duckduckgo.com/"

class WebSearchTool : ToolSet {

  @Tool(
    description =
      "Search the web for current information. Use this when the user asks about recent events, " +
        "factual data you may not know, or anything that requires up-to-date information. " +
        "Returns a summary and related results."
  )
  fun searchWeb(
    @ToolParam(description = "The search query string. Be concise and specific.") query: String
  ): Map<String, String> {
    return runBlocking(Dispatchers.IO) {
      try {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val urlStr = "$DDG_API?q=$encoded&format=json&no_html=1&skip_disambig=1&t=gallery-ai"
        Log.d(TAG, "Searching: $query")

        val connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.connectTimeout = 5000
        connection.readTimeout = 8000
        connection.connect()

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
          return@runBlocking mapOf("error" to "Search failed (HTTP ${connection.responseCode})")
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)

        val result = StringBuilder()

        val abstractText = json.optString("AbstractText", "")
        val abstractSource = json.optString("AbstractSource", "")
        if (abstractText.isNotEmpty()) {
          result.appendLine("**$abstractSource Summary**: $abstractText")
        }

        val answer = json.optString("Answer", "")
        if (answer.isNotEmpty()) {
          result.appendLine("**Direct Answer**: $answer")
        }

        val definition = json.optString("Definition", "")
        val definitionSource = json.optString("DefinitionSource", "")
        if (definition.isNotEmpty()) {
          result.appendLine("**Definition ($definitionSource)**: $definition")
        }

        val relatedTopics: JSONArray? = json.optJSONArray("RelatedTopics")
        if (relatedTopics != null && relatedTopics.length() > 0) {
          result.appendLine("\n**Related Results**:")
          var count = 0
          for (i in 0 until relatedTopics.length()) {
            if (count >= 5) break
            val topic = relatedTopics.optJSONObject(i) ?: continue
            val text = topic.optString("Text", "")
            val firstUrl = topic.optString("FirstURL", "")
            if (text.isNotEmpty()) {
              result.appendLine("- $text${if (firstUrl.isNotEmpty()) " [$firstUrl]" else ""}")
              count++
            }
          }
        }

        val results: JSONArray? = json.optJSONArray("Results")
        if (results != null && results.length() > 0) {
          result.appendLine("\n**Top Results**:")
          for (i in 0 until minOf(3, results.length())) {
            val r = results.optJSONObject(i) ?: continue
            val text = r.optString("Text", "")
            val firstUrl = r.optString("FirstURL", "")
            if (text.isNotEmpty()) {
              result.appendLine("- $text${if (firstUrl.isNotEmpty()) " [$firstUrl]" else ""}")
            }
          }
        }

        val finalResult = result.toString().trim()
        if (finalResult.isEmpty()) {
          mapOf("results" to "No results found for \"$query\". Try rephrasing your search.")
        } else {
          mapOf("results" to finalResult)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Search error", e)
        mapOf("error" to "Search failed: ${e.message}")
      }
    }
  }
}
