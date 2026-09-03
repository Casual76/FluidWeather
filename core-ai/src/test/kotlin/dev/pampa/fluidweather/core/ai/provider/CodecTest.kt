package dev.pampa.fluidweather.core.ai.provider

import dev.pampa.fluidweather.core.ai.net.RateLimitInfo
import dev.pampa.fluidweather.core.weather.asArray
import dev.pampa.fluidweather.core.weather.at
import dev.pampa.fluidweather.core.weather.get
import dev.pampa.fluidweather.core.weather.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {

  private val conversation = listOf(
    Message.System("sei un assistente"),
    Message.User("piove?"),
    Message.Assistant(null, listOf(ToolCall("call_a", "adesso", buildJsonObject { put("luogo", "qui") }), ToolCall("call_b", "radar_intorno", JsonObject(emptyMap()), thoughtSignature = "sig"))),
    Message.ToolResult("call_a", "adesso", "temperatura: 21 °C"),
    Message.ToolResult("call_b", "radar_intorno", "sul punto: nulla"),
    Message.Assistant("No, non piove.", emptyList()),
  )

  @Test
  fun `il dialetto OpenAI ha ruoli, tool_calls con argomenti stringa e tool con tool_call_id`() {
    val messages = OpenAiCompatCodec.messages(conversation)
    assertEquals("system", messages.at(0)["role"].string())
    val assistant = messages.at(2)
    assertEquals("assistant", assistant["role"].string())
    assertNull(assistant["content"].string())
    assertEquals("adesso", assistant["tool_calls"].at(0)["function"]["name"].string())
    assertEquals("""{"luogo":"qui"}""", assistant["tool_calls"].at(0)["function"]["arguments"].string())
    assertEquals("call_b", messages.at(4)["tool_call_id"].string())
    assertEquals("tool", messages.at(4)["role"].string())
    val tools = OpenAiCompatCodec.tools(listOf(ToolSpec("x", "fa x", buildJsonObject { put("type", "object") })))
    assertEquals("function", tools.at(0)["type"].string())
    assertEquals("x", tools.at(0)["function"]["name"].string())
  }

  @Test
  fun `una risposta OpenAI con due tool call parallele`() {
    val body = Json.parseToJsonElement(
      """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
        {"id":"c1","type":"function","function":{"name":"adesso","arguments":"{\"luogo\":\"qui\"}"}},
        {"id":"c2","type":"function","function":{"name":"sole","arguments":"{}"}}]},"finish_reason":"tool_calls"}],
        "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}""",
    )
    val turn = OpenAiCompatCodec.parseCompletion(body, RateLimitInfo.EMPTY, ProviderId.GROQ)
    assertEquals(FinishReason.TOOL_CALLS, turn.finishReason)
    assertEquals(listOf("adesso", "sole"), turn.message.toolCalls.map { it.name })
    assertEquals("qui", turn.message.toolCalls[0].arguments["luogo"].string())
    assertEquals(120, turn.usage?.totalTokens)
  }

  @Test
  fun `lo stream OpenAI ricompone tool call a frammenti e tiene uso e ragione`() {
    val state = OpenAiCompatCodec.StreamState()
    val assembler = ToolCallAssembler()
    val chunks = listOf(
      """{"choices":[{"delta":{"role":"assistant","content":"Controllo"}}]}""",
      """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"adesso","arguments":""}}]}}]}""",
      """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"luo"}}]}}]}""",
      """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"sole","arguments":"{}"}}]}}]}""",
      """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"go\":\"qui\"}"}}]}}]}""",
      """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
      """{"choices":[],"usage":{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}}""",
    )
    val text = StringBuilder()
    chunks.forEach { chunk ->
      OpenAiCompatCodec.parseStreamChunk(chunk, state).forEach { delta ->
        when (delta) {
          is ChatDelta.Text -> text.append(delta.text)
          is ChatDelta.ToolCallPart -> assembler.add(delta)
          else -> Unit
        }
      }
    }
    val calls = assembler.build()
    assertEquals("Controllo", text.toString())
    assertEquals(2, calls.size)
    assertEquals("qui", calls[0].arguments["luogo"].string())
    assertEquals("sole", calls[1].name)
    assertEquals("tool_calls", state.finish)
    assertEquals(60, state.usage?.totalTokens)
  }

  @Test
  fun `argomenti malformati non fanno cadere il giro`() {
    val parsed = ToolCallAssembler.parseArguments("{non json")
    assertEquals("{non json", parsed["_raw"].string())
    assertTrue(ToolCallAssembler.localCallId().startsWith("call_"))
  }

  @Test
  fun `il dialetto Gemini ha systemInstruction, functionCall con firma e functionResponse fusi in un turno`() {
    val system = GeminiCodec.systemInstruction(conversation)!!
    assertEquals("sei un assistente", system["parts"].at(0)["text"].string())
    val contents = GeminiCodec.contents(conversation)
    // user, model (2 functionCall), user (2 functionResponse), model (testo)
    assertEquals(4, contents.size)
    val model = contents.at(1)
    assertEquals("model", model["role"].string())
    val parts = model["parts"].asArray()
    assertEquals(2, parts.size)
    assertEquals(GeminiCodec.SKIP_SIGNATURE, parts[0]["thoughtSignature"].string())
    assertEquals("sig", parts[1]["thoughtSignature"].string())
    assertEquals("qui", parts[0]["functionCall"]["args"]["luogo"].string())
    val responses = contents.at(2)
    assertEquals("user", responses["role"].string())
    assertEquals(2, responses["parts"].asArray().size)
    assertEquals("radar_intorno", responses["parts"].at(1)["functionResponse"]["name"].string())
    assertEquals("No, non piove.", contents.at(3)["parts"].at(0)["text"].string())
  }

  @Test
  fun `le parti grezze di Gemini si rimandano identiche`() {
    val raw = Json.parseToJsonElement("""[{"functionCall":{"name":"adesso","args":{}},"thoughtSignature":"abc"}]""")
    val message = Message.Assistant(null, listOf(ToolCall("x", "adesso", JsonObject(emptyMap()))), raw = raw, rawProvider = ProviderId.GEMINI)
    val contents = GeminiCodec.contents(listOf(Message.User("?"), message))
    assertEquals("abc", contents.at(1)["parts"].at(0)["thoughtSignature"].string())
    val fromGroq = message.copy(rawProvider = ProviderId.GROQ)
    assertEquals(GeminiCodec.SKIP_SIGNATURE, GeminiCodec.contents(listOf(Message.User("?"), fromGroq)).at(1)["parts"].at(0)["thoughtSignature"].string())
  }

  @Test
  fun `una risposta Gemini con functionCall e usage`() {
    val body = Json.parseToJsonElement(
      """{"candidates":[{"content":{"role":"model","parts":[{"text":"Vedo."},{"functionCall":{"name":"sole","args":{"data":"oggi"}},"thoughtSignature":"s1"}]},"finishReason":"STOP"}],
        "usageMetadata":{"promptTokenCount":200,"candidatesTokenCount":30,"thoughtsTokenCount":70,"totalTokenCount":300}}""",
    )
    val turn = GeminiCodec.parseResponse(body, RateLimitInfo.EMPTY)
    assertEquals(FinishReason.TOOL_CALLS, turn.finishReason)
    assertEquals("Vedo.", turn.message.text)
    assertEquals("s1", turn.message.toolCalls[0].thoughtSignature)
    assertEquals("oggi", turn.message.toolCalls[0].arguments["data"].string())
    assertEquals(300, turn.usage?.totalTokens)
    assertEquals(100, turn.usage?.completionTokens)
    assertEquals(ProviderId.GEMINI, turn.message.rawProvider)
  }

  @Test
  fun `lo schema per Gemini perde le chiavi che non capisce`() {
    val schema = Json.parseToJsonElement("""{"type":"object","additionalProperties":false,"${'$'}schema":"x","properties":{"a":{"type":"string","default":"z","enum":["x"]}},"required":["a"]}""") as JsonObject
    val clean = GeminiSchema.sanitize(schema)
    assertNull(clean["additionalProperties"])
    assertNull(clean["\$schema"])
    assertNull(clean["properties"]["a"]["default"])
    assertEquals("x", clean["properties"]["a"]["enum"].at(0).string())
    assertEquals("a", clean["required"].at(0).string())
  }

  @Test
  fun `il catalogo Groq separa whisper dai chat e scarta guardie e voci`() {
    val body = Json.parseToJsonElement(
      """{"data":[{"id":"qwen/qwen3.8-27b","active":true,"context_window":131072,"max_completion_tokens":16384},
        {"id":"whisper-large-v3","active":true},{"id":"playai-tts","active":true},{"id":"meta-llama/llama-guard-4-12b","active":true},
        {"id":"llama-3.1-8b-instant","active":true,"context_window":131072},{"id":"old","active":false}]}""",
    )
    val catalogue = ModelCatalog.groq(body)
    assertEquals(listOf("qwen/qwen3.8-27b", "llama-3.1-8b-instant"), catalogue.chat.map { it.id })
    assertEquals(listOf("whisper-large-v3"), catalogue.stt.map { it.id })
  }

  @Test
  fun `il catalogo OpenRouter tiene solo i modelli con tool e riconosce i gratuiti`() {
    val body = Json.parseToJsonElement(
      """{"data":[
        {"id":"nvidia/nemotron-3.5-lightning:free","name":"Nemotron","context_length":131072,"architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"},"supported_parameters":["tools","reasoning"]},
        {"id":"poolside/laguna:free","name":"Laguna","context_length":32000,"architecture":{"input_modalities":["text"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0"},"supported_parameters":["temperature"]},
        {"id":"google/gemini-3.6-flash","name":"Gemini","context_length":1048576,"architecture":{"input_modalities":["text","audio"],"output_modalities":["text"]},"pricing":{"prompt":"0.00000075","completion":"0.00000375"},"supported_parameters":["tools","reasoning"]},
        {"id":"openai/whisper-large-v3","name":"Whisper","architecture":{"input_modalities":["audio"],"output_modalities":["text"]},"pricing":{"prompt":"0","completion":"0","audio":"0.0001"},"supported_parameters":[]}
      ]}""",
    )
    val catalogue = ModelCatalog.openRouter(body)
    assertEquals(listOf("nvidia/nemotron-3.5-lightning:free", "google/gemini-3.6-flash"), catalogue.chat.map { it.id })
    assertTrue(catalogue.chat[0].free && catalogue.chat[0].supportsReasoning)
    assertEquals(0.75, catalogue.chat[1].pricePromptPerM!!, 1e-9)
    assertTrue(catalogue.chat[1].audioInput)
    assertEquals(listOf("openai/whisper-large-v3"), catalogue.stt.map { it.id })
    assertEquals("nvidia/nemotron-3.5-lightning:free", OpenRouterCatalog.pickDefaultFree(catalogue)?.id)
    assertEquals("nvidia/nemotron-3.5-lightning:free", OpenRouterCatalog.recommended(catalogue).first().id)
  }

  @Test
  fun `l'uso si somma anche col costo`() {
    val a = Usage(10, 5, 15, costUsd = 0.001)
    val b = Usage(20, 5, 25)
    val sum = a + b
    assertEquals(40, sum.totalTokens)
    assertEquals(0.001, sum.costUsd!!, 1e-12)
    assertNull((Usage(1, 1, 2) + Usage(1, 1, 2)).costUsd)
    assertEquals(JsonPrimitive("auto"), OpenAiCompatCodec.toolChoice(ToolChoice.Auto))
  }
}
