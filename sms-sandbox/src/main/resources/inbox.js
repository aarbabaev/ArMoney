'use strict';
let token = '';
const el = id => document.getElementById(id);
function lock() {
  token = ''; el('token').value = ''; el('messages').replaceChildren(); el('inbox').hidden = true;
}
async function request(method = 'GET') {
  const query = new URLSearchParams({q: el('query').value, purpose: el('purpose').value});
  const response = await fetch('/api/messages?' + query, {method, cache: 'no-store', credentials: 'omit',
    headers: {Authorization: 'Bearer ' + token, ...(method === 'DELETE' ? {'X-Confirm-Clear': 'true'} : {})}});
  if (!response.ok) { if (response.status === 401) lock(); throw new Error('Request failed (' + response.status + ').'); }
  return response.status === 204 ? [] : response.json();
}
async function refresh() {
  const activeToken = token;
  try {
    const messages = await request();
    if (!token || token !== activeToken) return;
    el('messages').replaceChildren();
    for (const message of messages) {
      const article = document.createElement('article');
      for (const value of [message.destination, message.purpose, new Date(message.createdAt).toLocaleString(), message.text]) {
        const line = document.createElement('p'); line.textContent = value; article.append(line);
      }
      el('messages').append(article);
    }
    el('inbox').hidden = false; el('status').textContent = messages.length + ' messages';
  } catch (error) { el('messages').replaceChildren(); el('status').textContent = error.message; }
}
el('unlock').onclick = () => { token = el('token').value; el('token').value = ''; refresh(); };
el('lock').onclick = () => { lock(); el('status').textContent = 'Inbox locked'; };
el('refresh').onclick = refresh;
el('clear').onclick = async () => {
  if (!window.confirm('Permanently clear every inbox message? Retry protection remains until expiry.')) return;
  try { await request('DELETE'); await refresh(); } catch (error) { el('status').textContent = error.message; }
};
window.addEventListener('pagehide', lock);
