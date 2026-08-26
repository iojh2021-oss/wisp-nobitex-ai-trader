from pathlib import Path

p = Path("android/app/src/main/java/ai/wisp/trader/MainActivity.kt")
s = p.read_text()

old_title = '''title = {
                        Text(if (selectedTab == 0) "Wisp Trader" else "ChatGPT Workspace")
                    }'''
new_title = '''title = {
                        Text(when (selectedTab) {
                            0 -> "Wisp Trader"
                            1 -> "ChatGPT Workspace"
                            else -> "Nobitex Market"
                        })
                    }'''
if old_title not in s:
    raise SystemExit("MainActivity title block not found")
s = s.replace(old_title, new_title, 1)

old_nav = '''                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, "ChatGPT") },
                        label = { Text("ChatGPT") }
                    )'''
new_nav = old_nav + '''
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Outlined.Refresh, "Market") },
                        label = { Text("Market") }
                    )'''
if old_nav not in s:
    raise SystemExit("MainActivity navigation block not found")
s = s.replace(old_nav, new_nav, 1)

old_content = '''            if (selectedTab == 0) {
                TraderScreen(Modifier.padding(padding))
            } else {
                ChatGptBrowserScreen(Modifier.padding(padding))
            }'''
new_content = '''            when (selectedTab) {
                0 -> TraderScreen(Modifier.padding(padding))
                1 -> ChatGptBrowserScreen(Modifier.padding(padding))
                else -> NobitexMarketTab(Modifier.padding(padding))
            }'''
if old_content not in s:
    raise SystemExit("MainActivity content block not found")
s = s.replace(old_content, new_content, 1)

p.write_text(s)
print("wired successfully")
