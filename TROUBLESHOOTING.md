### Pre-Requisites

1. Ensure that the Jadx AI MCP server and plugin are installed, if not then follow the instruction mentioned here -> [how_to_install](https://github.com/zinja-coder/jadx-ai-mcp?tab=readme-ov-file#%EF%B8%8F-getting-started)

### Verify the JADX AI MCP Plugin

1. Open jadx gui.
2. Load any apk. For example, following test apk can be used -> [DVAC](https://github.com/zinja-coder/Damn-Vulnerable-Android-Components/releases)
3. After APK is decompiled, analyze the logs for any errors. If there are not exception then everything is fine on plugin side.
4. For example, following image shows the sample without any issues.

<img width="1913" height="1030" alt="image" src="https://github.com/user-attachments/assets/3df159ee-3168-43bf-8e31-572605b6965f" />

### Verify the JADX AI MCP Server 

1. Navigate to the folder/directory where `jadx_mcp_server.py` is residing.
2. Ensure that jadx-gui is running and you have completed the verification of plugin.
3. Run the following command -> `uv run jadx_mcp_server.py --jadx-port <port>`, if there are no errors then connection between jadx_mcp_server and plugin is ok.
4. If plugin and mcp server are running on different machines, use -> `uv run jadx_mcp_server.py --jadx-host <plugin_host_ip> --jadx-port <port>`

<img width="1383" height="324" alt="image" src="https://github.com/user-attachments/assets/f488947f-4d5a-4f5e-ba3c-116813a973d7" />

### Verify MCP Tools calling

1. 
