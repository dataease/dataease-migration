const form = document.querySelector('#migration-form');
const submitButton = document.querySelector('#submit-button');
const logs = document.querySelector('#logs');

function value(data, name) {
  return data.get(name).trim();
}

function requestFrom(formData) {
  return {
    copySyncTaskLogs: formData.get('copySyncTaskLogs') === 'on',
    sourceServer: {
      host: value(formData, 'sourceServer.host'),
      port: Number(value(formData, 'sourceServer.port')),
      username: value(formData, 'sourceServer.username'),
      password: value(formData, 'sourceServer.password'),
      installPath: value(formData, 'sourceServer.installPath')
    },
    sourceDatabase: {
      jdbcUrl: value(formData, 'sourceDatabase.jdbcUrl'),
      username: value(formData, 'sourceDatabase.username'),
      password: value(formData, 'sourceDatabase.password')
    },
    targetServer: {
      host: value(formData, 'targetServer.host'),
      port: Number(value(formData, 'targetServer.port')),
      username: value(formData, 'targetServer.username'),
      password: value(formData, 'targetServer.password'),
      installPath: value(formData, 'targetServer.installPath')
    },
    targetDatabase: {
      jdbcUrl: value(formData, 'targetDatabase.jdbcUrl'),
      username: value(formData, 'targetDatabase.username'),
      password: value(formData, 'targetDatabase.password')
    }
  };
}

function appendLog(line) {
  logs.textContent += `${logs.textContent ? '\n' : ''}${line}`;
  logs.scrollTop = logs.scrollHeight;
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!form.reportValidity()) return;

  submitButton.disabled = true;
  logs.textContent = '正在创建迁移任务...';
  try {
    const response = await fetch('/api/migrations', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(requestFrom(new FormData(form)))
    });
    const payload = await response.json();
    if (!response.ok) throw new Error(payload.message || '创建迁移任务失败');

    logs.textContent = '';
    const events = new EventSource(`/api/migrations/${payload.id}/events`);
    events.addEventListener('log', (message) => appendLog(message.data));
    events.addEventListener('complete', (message) => {
      appendLog(message.data);
      events.close();
      submitButton.disabled = false;
    });
    events.onerror = () => {
      if (events.readyState === EventSource.CLOSED) {
        submitButton.disabled = false;
      }
    };
  } catch (error) {
    logs.textContent = `无法启动迁移：${error.message}`;
    submitButton.disabled = false;
  }
});
