import { modelEndpoint } from './model.js';
export const defaults = Object.freeze({ endpoint: 'https://worldcodes.online/v1', model: 'deepseek-v4.1-flash', outputTokens: 0, disableThinking: true, lightGlm: true, depth: 'standard', rememberKey: false });
export async function readSettings() {
  const local = await chrome.storage.local.get(['settings', 'modelKey']), session = await chrome.storage.session.get('modelKey');
  const settings = { ...defaults, ...local.settings };
  return { ...settings, apiKey: settings.rememberKey ? local.modelKey || '' : session.modelKey || '' };
}
export function permissionPattern(endpoint) {
  const url = new URL(modelEndpoint(endpoint));
  return `${url.protocol}//${url.hostname}/*`;
}
export async function saveSettings(input) {
  const { apiKey = '', ...settings } = input;
  await chrome.storage.local.set({ settings });
  if (settings.rememberKey) { await chrome.storage.local.set({ modelKey: apiKey }); await chrome.storage.session.remove('modelKey'); }
  else { await chrome.storage.session.set({ modelKey: apiKey }); await chrome.storage.local.remove('modelKey'); }
}
export async function forgetKey() { await chrome.storage.local.remove('modelKey'); await chrome.storage.session.remove('modelKey'); }
