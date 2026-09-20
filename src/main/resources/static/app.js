'use strict';

const list = document.getElementById('task-list');
const template = document.getElementById('task-template');
const form = document.getElementById('task-form');
const titleInput = document.getElementById('title');
const descriptionInput = document.getElementById('description');
const sourcePill = document.getElementById('source-pill');
const readoutText = document.getElementById('readout-text');
const errorBox = document.getElementById('error');
const emptyNote = document.getElementById('empty');
const infoLine = document.getElementById('info-line');

async function api(path, options) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body && body.message) message = body.message;
    } catch (ignored) { /* keep the status line */ }
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json();
}

function showError(message) {
  errorBox.textContent = message;
  errorBox.hidden = !message;
}

function renderReadout(payload) {
  const fromCache = payload.source === 'cache';
  sourcePill.textContent = fromCache ? 'Redis cache' : 'PostgreSQL';
  sourcePill.className = `pill ${fromCache ? 'pill-cache' : 'pill-db'}`;
  // The timestamp is what tells you a refresh actually happened when the list
  // itself has not changed.
  readoutText.textContent =
    `${payload.count} task${payload.count === 1 ? '' : 's'} in ${payload.elapsedMs} ms` +
    (fromCache ? ' — served from ElastiCache' : ' — read through RDS Proxy, cache repopulated') +
    ` · ${new Date().toLocaleTimeString()}`;
}

function renderTasks(tasks) {
  list.replaceChildren();
  emptyNote.hidden = tasks.length > 0;

  for (const task of tasks) {
    const node = template.content.firstElementChild.cloneNode(true);
    node.classList.toggle('done', task.completed);

    const checkbox = node.querySelector('.task-done');
    checkbox.checked = task.completed;
    checkbox.addEventListener('change', () => toggle(task, checkbox.checked));

    node.querySelector('.task-title').textContent = task.title;
    const description = node.querySelector('.task-description');
    if (task.description) {
      description.textContent = task.description;
    } else {
      description.remove();
    }

    wireEditing(node, task);
    node.querySelector('.task-delete').addEventListener('click', () => remove(task));
    list.append(node);
  }
}

/**
 * Editing happens in place, on this row only: the body is swapped for two
 * inputs pre-filled with the current values, so changing the notes does not
 * mean retyping the title, and no other task is touched.
 */
function wireEditing(node, task) {
  const body = node.querySelector('.task-body');
  const editor = node.querySelector('.task-editor');
  const viewActions = node.querySelector('.task-actions:not(.editor-actions)');
  const editActions = node.querySelector('.editor-actions');
  const titleField = node.querySelector('.edit-title');
  const descriptionField = node.querySelector('.edit-description');

  function setEditing(on) {
    node.classList.toggle('editing', on);
    body.hidden = on;
    editor.hidden = !on;
    viewActions.hidden = on;
    editActions.hidden = !on;
  }

  function open() {
    // One editor at a time, so the page never shows two half-finished edits.
    for (const other of list.querySelectorAll('.task.editing')) {
      if (other !== node) other.querySelector('.task-cancel').click();
    }
    titleField.value = task.title;
    descriptionField.value = task.description || '';
    setEditing(true);
    titleField.focus();
    titleField.select();
  }

  async function save() {
    const title = titleField.value.trim();
    if (!title) {
      titleField.focus();
      showError('A task needs a title.');
      return;
    }
    editActions.querySelectorAll('button').forEach((b) => (b.disabled = true));
    try {
      await api(`/api/tasks/${task.id}`, {
        method: 'PUT',
        body: JSON.stringify({
          title,
          description: descriptionField.value.trim() || null,
          completed: task.completed,
        }),
      });
      showError('');
      await load();
    } catch (e) {
      showError(`Could not update the task: ${e.message}`);
      editActions.querySelectorAll('button').forEach((b) => (b.disabled = false));
    }
  }

  node.querySelector('.task-edit').addEventListener('click', open);
  node.querySelector('.task-save').addEventListener('click', save);
  node.querySelector('.task-cancel').addEventListener('click', () => {
    setEditing(false);
    showError('');
  });

  // Enter saves, Escape abandons - the form element makes Enter submit.
  editor.addEventListener('submit', (event) => {
    event.preventDefault();
    save();
  });
  editor.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      setEditing(false);
      showError('');
    }
  });
}

async function load({ fresh = false } = {}) {
  try {
    const payload = await api(fresh ? '/api/tasks?refresh=true' : '/api/tasks');
    renderReadout(payload);
    renderTasks(payload.items);
    showError('');
  } catch (e) {
    showError(`Could not load tasks: ${e.message}`);
  }
}

async function loadInfo() {
  try {
    const info = await api('/api/info');
    infoLine.textContent =
      `${info.environment} · ${info.region} · cache ${info.cacheReachable ? 'reachable' : 'unreachable'} ` +
      `(TTL ${info.cacheTtlSeconds}s) · credentials from ${info.credentialSource.split(':')[0]}`;
  } catch (e) {
    infoLine.textContent = '';
  }
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const title = titleInput.value.trim();
  if (!title) return;
  try {
    await api('/api/tasks', {
      method: 'POST',
      body: JSON.stringify({ title, description: descriptionInput.value.trim() || null, completed: false }),
    });
    form.reset();
    titleInput.focus();
    await load();
  } catch (e) {
    showError(`Could not add the task: ${e.message}`);
  }
});

async function toggle(task, completed) {
  try {
    await api(`/api/tasks/${task.id}`, {
      method: 'PUT',
      body: JSON.stringify({ title: task.title, description: task.description, completed }),
    });
    await load();
  } catch (e) {
    showError(`Could not update the task: ${e.message}`);
    await load();
  }
}

async function remove(task) {
  if (!window.confirm(`Delete "${task.title}"?`)) return;
  try {
    await api(`/api/tasks/${task.id}`, { method: 'DELETE' });
    await load();
  } catch (e) {
    showError(`Could not delete the task: ${e.message}`);
  }
}

// Refresh bypasses the cache. Re-reading it would return the same snapshot for
// up to the TTL, so a change made anywhere else stays invisible and the button
// looks broken.
const refreshButton = document.getElementById('refresh');
refreshButton.addEventListener('click', async () => {
  const label = refreshButton.textContent;
  refreshButton.disabled = true;
  refreshButton.textContent = 'Refreshing…';
  try {
    await load({ fresh: true });
  } finally {
    refreshButton.disabled = false;
    refreshButton.textContent = label;
  }
});

document.getElementById('flush').addEventListener('click', async () => {
  try {
    const result = await api('/api/cache/flush', { method: 'POST' });
    showError('');
    readoutText.textContent = `Removed ${result.keysRemoved} cache key(s). The next read will hit PostgreSQL.`;
    sourcePill.textContent = '—';
    sourcePill.className = 'pill pill-idle';
  } catch (e) {
    showError(`Could not flush the cache: ${e.message}`);
  }
});

load();
loadInfo();
setInterval(loadInfo, 30000);
