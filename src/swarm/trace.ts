import * as fs from "fs";
import * as os from "os";
import * as path from "path";
import type { SwarmEvent } from "./types.js";

function chorusHome(): string {
  return process.env.CHORUS_HOME_DIR ?? path.join(os.homedir(), ".chorus");
}

export class SwarmTracer {
  private readonly filePath: string;
  private readonly buf: string[] = [];
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly exitHandler: () => void;

  constructor(swarmId: string) {
    const dir = path.join(chorusHome(), "swarm-traces");
    fs.mkdirSync(dir, { recursive: true });
    this.filePath = path.join(dir, `${swarmId}.jsonl`);

    // Flush on process exit so the last events of a failed/aborted swarm are not lost.
    this.exitHandler = () => this.flush();
    process.once("exit", this.exitHandler);
  }

  record(event: SwarmEvent): void {
    this.buf.push(JSON.stringify({ ts: Date.now(), ...event }));
    if (this.flushTimer === null) {
      this.flushTimer = setTimeout(() => this.flush(), 200);
      // unref() prevents the timer from keeping the process alive when everything else is done.
      this.flushTimer.unref();
    }
  }

  flush(): void {
    if (this.flushTimer !== null) {
      clearTimeout(this.flushTimer);
      this.flushTimer = null;
    }
    if (this.buf.length === 0) return;
    const lines = this.buf.splice(0).join("\n") + "\n";
    try {
      fs.appendFileSync(this.filePath, lines, "utf8");
    } catch {
      /* never crash on trace write */
    }
    // Remove exit handler once we've flushed successfully.
    process.removeListener("exit", this.exitHandler);
  }
}
