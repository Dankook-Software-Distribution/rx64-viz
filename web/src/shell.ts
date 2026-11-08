// The one xv6 shell: which kernel it runs, its state and the commands
// recorded in it. Opening the page attaches to the running shell, or
// boots one. New recordings arrive through the console socket (which
// calls refresh); polling only runs while a boot recording is being
// prepared.

import { api, type KernelInfo, type SessionInfo } from "./api";
import { load, save } from "./storage";
import { store } from "./store";

export interface ShellState {
  kernels: KernelInfo[];
  shell: SessionInfo | null;
  /** shells replaced earlier (newest first): their recordings can still be opened */
  past: SessionInfo[];
  /** kernel shown in the title bar (the shell's, or the one being booted) */
  kernel: string | null;
  booting: boolean;
  error: string | null;
}

const KERNEL_KEY = "rxviz.kernel";
const POLL_MS = 2000;

class Shell {
  private s = store<ShellState>({ kernels: [], shell: null, past: [], kernel: null, booting: false, error: null });
  private timer: number | undefined;

  get value() {
    return this.s.value;
  }

  subscribe(fn: (s: ShellState) => void): () => void {
    return this.s.subscribe(fn);
  }

  private set(p: Partial<ShellState>) {
    this.s.set({ ...this.s.value, ...p });
  }

  async start() {
    await this.refresh();
    const { shell, kernels } = this.s.value;
    if (!shell && kernels.length) {
      const saved = load(KERNEL_KEY);
      await this.boot(kernels.some((k) => k.id === saved) ? saved! : kernels[0].id);
    }
  }

  async refresh() {
    window.clearTimeout(this.timer);
    try {
      const [kernels, shell, past] = await Promise.all([api.kernels(), api.shell(), api.pastShells()]);
      this.set({ kernels, shell, past, kernel: shell?.kernel ?? this.s.value.kernel, error: null });
      const preparing = kernels.some((k) => k.boot.status !== "READY" && k.boot.status !== "FAILED");
      if (preparing) this.timer = window.setTimeout(() => void this.refresh(), POLL_MS);
    } catch (e) {
      this.set({ error: "Server not reachable: " + (e as Error).message });
      this.timer = window.setTimeout(() => void this.refresh(), POLL_MS);
    }
  }

  /** Boot a fresh shell on kernel k (the current one stops). */
  async boot(k: string) {
    this.set({ booting: true, kernel: k });
    save(KERNEL_KEY, k);
    try {
      const shell = await api.newShell(k);
      const past = await api.pastShells().catch(() => this.s.value.past);
      this.set({ shell, past, booting: false });
    } catch (e) {
      this.set({ booting: false, error: "Cannot start xv6: " + (e as Error).message });
    }
  }
}

export const shell = new Shell();
