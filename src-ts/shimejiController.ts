import * as fs from 'fs';
import * as path from 'path';

// eslint-disable-next-line @typescript-eslint/no-var-requires
const config = require('../src/config');

function commandFile(characterId: string): string {
  return path.join(config.workspaceDir, `ai-command-${characterId}.json`);
}

function statusFile(characterId: string): string {
  return path.join(config.workspaceDir, `ai-status-${characterId}.json`);
}

export interface ShimejiCommand {
  id: number;
  tool: string;
  args: Record<string, unknown>;
}

export interface ShimejiStatus {
  x: number;
  y: number;
  moving: boolean;
  lookRight: boolean;
  pose: string;
  commandId: string | null;
}

/**
 * Sends a command to one Java-side AI mascot (its AIBehavior polls this file
 * every ~200ms). Writes to a temp file first and renames so the Java side
 * never reads a half-written file. characterId must match the id used on the
 * Java side (com.stickmanai.CommandWatcher.forId).
 */
export function sendCommand(characterId: string, tool: string, args: Record<string, unknown> = {}): void {
  const command: ShimejiCommand = { id: Date.now(), tool, args };
  const file = commandFile(characterId);
  const tmpFile = `${file}.tmp`;
  fs.writeFileSync(tmpFile, JSON.stringify(command), 'utf8');
  fs.renameSync(tmpFile, file);
}

/** Reads one mascot's last known on-screen position/state, if available. */
export function readStatus(characterId: string): ShimejiStatus | null {
  try {
    const text = fs.readFileSync(statusFile(characterId), 'utf8');
    return JSON.parse(text) as ShimejiStatus;
  } catch {
    return null;
  }
}
