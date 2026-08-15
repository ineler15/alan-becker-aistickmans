import { screen, imageToJimp } from '@nut-tree-fork/nut-js';

export interface ActiveWindowInfo {
  title: string;
  ownerName: string;
  bounds: { x: number; y: number; width: number; height: number };
}

export interface Perception {
  screenshot: any; // Jimp image, kept in memory so each character can crop its own view from it
  activeWindow: ActiveWindowInfo | null;
}

export interface CharacterPosition {
  x: number;
  y: number;
}

const DEFAULT_VIEW_WIDTH = 560;
const DEFAULT_VIEW_HEIGHT = 360;

async function getActiveWindow(): Promise<ActiveWindowInfo | null> {
  try {
    const activeWin = await import('active-win');
    const win = await activeWin.default();
    if (!win) return null;
    return {
      title: win.title,
      ownerName: win.owner.name,
      bounds: win.bounds,
    };
  } catch {
    return null;
  }
}

async function captureScreenshotJimp(): Promise<any> {
  const image = await screen.grab();
  return imageToJimp(image);
}

/** Captures a full-screen screenshot plus the active window, so the AI decides with real context instead of acting blind. */
export async function capturePerception(): Promise<Perception> {
  const [screenshot, activeWindow] = await Promise.all([
    captureScreenshotJimp(),
    getActiveWindow(),
  ]);
  return { screenshot, activeWindow };
}

/**
 * Crops the shared screenshot to a window centered on one character's on-screen
 * position, so that character's AI sees its own surroundings instead of the
 * whole desktop - each stickman gets its own point of view instead of a
 * shared god's-eye screenshot.
 */
export async function cropForCharacter(
  screenshot: any,
  position: CharacterPosition | null,
  viewWidth: number = DEFAULT_VIEW_WIDTH,
  viewHeight: number = DEFAULT_VIEW_HEIGHT,
): Promise<string> {
  const fullWidth = screenshot.bitmap.width;
  const fullHeight = screenshot.bitmap.height;

  const w = Math.min(viewWidth, fullWidth);
  const h = Math.min(viewHeight, fullHeight);

  // Aim a bit above the character's anchor (their feet) instead of centering
  // on it, so it sees more of what's in front of it than the ground below.
  const centerX = position ? position.x : fullWidth / 2;
  const centerY = position ? position.y - h / 3 : fullHeight / 2;

  const left = Math.max(0, Math.min(fullWidth - w, Math.round(centerX - w / 2)));
  const top = Math.max(0, Math.min(fullHeight - h, Math.round(centerY - h / 2)));

  const cropped = screenshot.clone().crop(left, top, w, h);
  const dataUri = await cropped.getBase64Async('image/png');
  return dataUri.replace(/^data:image\/png;base64,/, '');
}
