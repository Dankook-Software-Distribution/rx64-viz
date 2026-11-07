import "@vscode/codicons/dist/codicon.css";
import "@xterm/xterm/css/xterm.css";
import "./vendor/dockview.css";
import "./style.css";

import { statusbar, toolbar } from "./chrome";
import { createLayout, resetLayout, setLayoutTheme } from "./layout";
import { shell } from "./shell";
import { load, save } from "./storage";

const THEME_KEY = "rxviz.theme";

const savedTheme = load(THEME_KEY);
let dark = savedTheme ? savedTheme === "dark" : !window.matchMedia("(prefers-color-scheme: light)").matches;
document.documentElement.dataset.theme = dark ? "dark" : "light";

const api = createLayout(document.getElementById("dock")!, dark);

toolbar(document.getElementById("toolbar")!, {
  resetLayout: () => resetLayout(api),
  toggleTheme: () => {
    dark = !dark;
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    setLayoutTheme(api, dark);
    save(THEME_KEY, dark ? "dark" : "light");
    window.dispatchEvent(new Event("rx-theme"));
  },
});
statusbar(document.getElementById("statusbar")!);
void shell.start();
