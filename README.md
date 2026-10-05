# Moag (Mobile Agent)

Moag is a headless Android agent powered by the Gemini API that runs continuously in the background to achieve user tasks.

## Requirements Checklist
- [x] Must run headless in a background foreground service.
- [x] Must survive the app being swiped away or minimized.
- [x] Settings menu to securely input and store the Gemini API Key.
- [x] Tabbed UI allowing multiple concurrent background agent tasks.
- [x] Live UI updates for agent status (Working Spinner, Done Checkmark).
- [x] Expandable/collapsible "Thoughts" view to inspect agent tool execution and HTTP errors.
- [x] Give tabs a 1-2 word summarized title dynamically based on the prompt.
- [x] Allow closing a tab with a long-press.
- [x] Show the history of the conversation, leaving the thought stream hidden or separate.
- [x] Let user share files (e.g. images) via Android Sharesheet into the app, placing paths in prompt.
- [x] Always ask for permission via a dialog box showing path and preview before `write_file` or sharing to another app.

## Manual Integration Test Suite
The following manual integration tests should be run on an Android device or emulator to verify the agent's core capabilities.

### 1. Location & Context Gathering
- **Prompt**: "how high is the tide right now?"
- **Expected Behavior**: The agent fetches the device's location. If the location is inland (e.g. Lyon in the emulator), the agent should respond conversationally to state that the current location has no tide and ask the user to clarify which coastal city they want to check.
- **Verification**: Check the UI transcript for the conversational response.

### 2. Device Control & Camera
- **Prompt**: "take a picture"
- **Expected Behavior**: The agent invokes the `take_picture` tool without opening any Camera preview UI. The image is saved silently, and the agent responds with the absolute file path of the saved picture.
- **Verification**: Check logcat for the tool invocation, and verify the UI shows the resulting path.

### 3. Image Analysis (Requires Step 2)
- **Prompt**: "what does my last picture show?"
- **Expected Behavior**: The agent uses `list_dir` or its memory to find the picture taken in the previous step, reads it, and describes the contents. Since it's an emulator, the picture might be a black image or a default emulator 3D scene.
- **Verification**: Ensure the agent correctly identifies the image content and responds conversationally.

### 4. Web Fetching & File Writing
- **Prompt**: "write today's news to a text file in my downloads folder"
- **Expected Behavior**: The agent fetches current news via the web search tool, then attempts to use `write_text_file`. A **Permission Dialog** must appear on screen showing the target path and file contents. Once the user clicks "Allow", the file is written.
- **Verification**: Ensure the UI permission overlay appears. Check the Downloads folder (`/sdcard/Download/`) to confirm the text file was written.

### 5. Media Generation & File Writing
- **Prompt**: "generate an image of a duckling to my downloads folder"
- **Expected Behavior**: The agent generates an image using its internal capabilities, then attempts to save it. A **Permission Dialog** must appear. Once approved, the image is saved.
- **Verification**: Check the Downloads folder to verify the `duckling.png` image exists and is a valid image.

### 6. App Interaction via Intents
- **Prompt**: "upload my latest flower pic to Commons with caption 'Yellow flower'"
- **Expected Behavior**: The agent queries the device camera folder, selects the appropriate photo, asks for permission to launch an intent, and opens the Commons app passing both the image and the text caption.
- **Verification**: Ensure the Commons app opens to the upload screen with the chosen picture and the caption text populated.
