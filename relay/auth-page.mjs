// The browser only receives the WebAuthn ticket and the short-lived return receipt.
// The polling secret stays in the native app.
const script = String.raw`
const ticket = location.hash.slice(1);
history.replaceState(null, '', location.pathname);
addEventListener('hashchange', () => {
  if (/^#[A-Za-z0-9_-]{43}$/.test(location.hash)) location.reload();
});
const buttons = document.querySelector('#choices');
const status = document.querySelector('#status');
const back = document.querySelector('#return');
const available = /^[A-Za-z0-9_-]{43}$/.test(ticket);
function disable(value) {
  for (const button of buttons.querySelectorAll('button')) button.disabled = value;
}
function decode(value) {
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));
}
function encode(value) {
  return btoa(Array.from(new Uint8Array(value), n => String.fromCharCode(n)).join(''))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
function optionsForBrowser(options) {
  options.challenge = decode(options.challenge);
  if (options.user) options.user.id = decode(options.user.id);
  for (const list of [options.allowCredentials, options.excludeCredentials]) {
    for (const credential of list || []) credential.id = decode(credential.id);
  }
  return options;
}
function credentialJson(credential) {
  const response = credential.response;
  const data = {clientDataJSON: encode(response.clientDataJSON)};
  if (response.attestationObject) {
    data.attestationObject = encode(response.attestationObject);
    if (response.getTransports) data.transports = response.getTransports();
  } else {
    data.authenticatorData = encode(response.authenticatorData);
    data.signature = encode(response.signature);
    if (response.userHandle) data.userHandle = encode(response.userHandle);
  }
  return {id: credential.id, rawId: encode(credential.rawId), type: credential.type,
    response: data, clientExtensionResults: credential.getClientExtensionResults()};
}
async function call(path, body) {
  const response = await fetch('/v1/auth/' + path, {
    method: 'POST', mode: 'same-origin', redirect: 'error', credentials: 'omit', referrerPolicy: 'origin',
    headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
  });
  if (!response.ok) {
    if (response.status === 410) throw new Error('This sign-in expired. Start again in Jot.');
    if (response.status === 429) throw new Error('Too many attempts. Try again later.');
    throw new Error('Couldn’t finish signing in. Try again.');
  }
  return response.json();
}
async function authorize(mode) {
  disable(true);
  status.textContent = 'Waiting for your passkey…';
  try {
    const {publicKey} = await call('options', {ticket, mode});
    const options = {publicKey: optionsForBrowser(publicKey)};
    const credential = mode === 'register'
      ? await navigator.credentials.create(options) : await navigator.credentials.get(options);
    const {receipt} = await call('verify', {ticket, credential: credentialJson(credential)});
    if (!/^[A-Za-z0-9_-]{43}$/.test(receipt)) throw new Error('Start again in Jot.');
    buttons.hidden = true;
    document.querySelector('h1').textContent = 'You’re signed in';
    status.textContent = '';
    back.href = 'jot://account/return#' + receipt;
    back.hidden = false;
    back.focus();
  } catch (error) {
    status.textContent = error.name === 'NotAllowedError' || error.name === 'AbortError'
      ? 'Passkey request canceled. Try again.' : error.name === 'Error'
      ? error.message : 'Couldn’t use this passkey. Try again.';
    disable(false);
  }
}
if (!available || !window.PublicKeyCredential) {
  buttons.hidden = true;
  status.textContent = available ? 'Open this page in a browser that supports passkeys.' : 'Start sign-in from the Jot app.';
} else {
  document.querySelector('#register').addEventListener('click', () => authorize('register'));
  document.querySelector('#login').addEventListener('click', () => authorize('login'));
}
`;

export function authenticationPage(nonce) {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<title>Sign in to Jot</title>
<style nonce="${nonce}">
:root{color-scheme:light dark;--bg:#fff;--ink:#1d2026;--muted:#565d68;--accent:#315dc0;--on:#fff;--tint:#eef1f6;font-family:Figtree,system-ui,sans-serif}
@media(prefers-color-scheme:dark){:root{--bg:#111315;--ink:#f0f1f4;--muted:#bec3cd;--accent:#adc4ff;--on:#122348;--tint:#262b32}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink)}
main{width:min(100%,27rem);margin:clamp(2rem,11vh,7rem) auto;padding:1.5rem}
h1{font-size:clamp(2rem,7vw,2.8rem);line-height:1.12;letter-spacing:-.045em;margin:0 0 2rem}
button,a{display:block;width:100%;padding:.95rem 1rem;border:0;border-radius:1rem;font:600 1.05rem/1.35 system-ui,sans-serif;text-align:center;cursor:pointer;text-decoration:none;background:var(--accent);color:var(--on)}
#register{margin-top:.65rem;background:var(--tint);color:var(--ink)}button:disabled{opacity:.6;cursor:default}
:focus-visible{outline:3px solid var(--accent);outline-offset:4px}p{color:var(--muted);line-height:1.5}
[hidden]{display:none}
</style></head>
<body><main><h1>Sign in to Jot</h1>
<div id="choices"><button id="login" type="button">Sign in</button><button id="register" type="button">Create account</button></div>
<p id="status" role="status" aria-live="polite"></p><a id="return" hidden rel="noreferrer">Return to Jot</a>
<noscript>Enable JavaScript to use a passkey.</noscript></main><script nonce="${nonce}">${script}</script></body></html>`;
}
