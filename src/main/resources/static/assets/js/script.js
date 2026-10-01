'use strict';

const $ = id => document.getElementById(id);
const isValidEmail = email => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim());

function showFieldError(errId, inputEl, message) {
  const errEl = $(errId);
  if (errEl) errEl.textContent = message;
  if (inputEl) {
    inputEl.classList.add('input-error');
    inputEl.classList.remove('input-success');
  }
}

function clearFieldError(errId, inputEl) {
  const errEl = $(errId);
  if (errEl) errEl.textContent = '';
  if (inputEl) inputEl.classList.remove('input-error');
}

function markFieldValid(inputEl) {
  if (inputEl) {
    inputEl.classList.remove('input-error');
    inputEl.classList.add('input-success');
  }
}

function setGlobalError(errId, message) {
  const el = $(errId);
  if (!el) return;
  el.textContent = message || '';
  el.classList.toggle('show', Boolean(message));
}

function setButtonLoading(btnId, isLoading) {
  const btn = $(btnId);
  if (!btn) return;
  const text = btn.querySelector('.btn-text');
  const spin = btn.querySelector('.btn-loader');
  btn.disabled = isLoading;
  if (text) text.hidden = isLoading;
  if (spin) spin.hidden = !isLoading;
}

async function apiRequest(url, payload) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload || {})
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || 'Request failed. Please try again.');
  return data;
}

function switchTab(tab) {
  const loginForm = $('login-form');
  const registerForm = $('register-form');
  const tabLogin = $('tab-login');
  const tabRegister = $('tab-register');
  const slider = $('tab-slider');

  resetLoginForm();
  resetRegisterForm();

  const loginActive = tab === 'login';
  loginForm.classList.toggle('active', loginActive);
  registerForm.classList.toggle('active', !loginActive);
  tabLogin.classList.toggle('active', loginActive);
  tabRegister.classList.toggle('active', !loginActive);
  slider.classList.toggle('right', !loginActive);
}

function resetLoginForm() {
  $('login-form')?.reset();
  clearFieldError('login-email-err', $('login-email'));
  clearFieldError('login-password-err', $('login-password'));
  setGlobalError('login-global-err', '');
  setButtonLoading('login-btn', false);
  $('login-email')?.classList.remove('input-success');
  $('login-password')?.classList.remove('input-success');
}

function resetRegisterForm() {
  $('register-form')?.reset();
  clearFieldError('reg-name-err', $('reg-name'));
  clearFieldError('reg-email-err', $('reg-email'));
  clearFieldError('reg-password-err', $('reg-password'));
  clearFieldError('reg-confirm-err', $('reg-confirm'));
  setGlobalError('reg-global-err', '');
  setButtonLoading('register-btn', false);
  updateStrengthBar('');
  ['reg-name', 'reg-email', 'reg-password', 'reg-confirm'].forEach(id => {
    $(id)?.classList.remove('input-success', 'input-error');
  });
}

function togglePassword(inputId, btn) {
  const input = $(inputId);
  if (!input) return;
  input.type = input.type === 'password' ? 'text' : 'password';
  btn.setAttribute('aria-label', input.type === 'password' ? 'Show password' : 'Hide password');
}

function updateStrengthBar(password) {
  const fill = $('strength-fill');
  const label = $('strength-label');
  const wrap = $('strength-wrap');
  if (!fill || !label || !wrap) return;

  if (!password) {
    wrap.classList.remove('visible');
    fill.style.width = '0%';
    label.textContent = '';
    return;
  }

  wrap.classList.add('visible');
  let score = 0;
  if (password.length >= 8) score++;
  if (password.length >= 12) score++;
  if (/[A-Z]/.test(password) && /[a-z]/.test(password)) score++;
  if (/[0-9]/.test(password)) score++;
  if (/[^A-Za-z0-9]/.test(password)) score++;
  score = Math.min(score, 4);

  const levels = [
    { pct: '20%', color: '#c0392b', text: 'Weak' },
    { pct: '40%', color: '#e67e22', text: 'Fair' },
    { pct: '70%', color: '#f1c40f', text: 'Good' },
    { pct: '100%', color: '#27ae60', text: 'Strong' }
  ];
  const level = levels[score - 1] || { pct: '5%', color: '#e8a0a0', text: '' };
  fill.style.width = level.pct;
  fill.style.backgroundColor = level.color;
  label.textContent = level.text;
  label.style.color = level.color;
}

function saveSession(user) {
  sessionStorage.setItem('sp_current_user', JSON.stringify({
    token: user.token,
    name: user.name || user.email || 'User',
    email: user.email
  }));
}

function openDashboard(user) {
  saveSession(user);
  window.location.href = '/dashboard.html';
}

$('login-form')?.addEventListener('submit', async e => {
  e.preventDefault();
  setGlobalError('login-global-err', '');

  const emailEl = $('login-email');
  const passwordEl = $('login-password');
  const email = emailEl.value.trim();
  const password = passwordEl.value;
  let valid = true;

  if (!email) {
    showFieldError('login-email-err', emailEl, 'Email is required.');
    valid = false;
  } else if (!isValidEmail(email)) {
    showFieldError('login-email-err', emailEl, 'Please enter a valid email address.');
    valid = false;
  } else {
    clearFieldError('login-email-err', emailEl);
    markFieldValid(emailEl);
  }

  if (!password) {
    showFieldError('login-password-err', passwordEl, 'Password is required.');
    valid = false;
  } else if (password.length < 6) {
    showFieldError('login-password-err', passwordEl, 'Password must be at least 6 characters.');
    valid = false;
  } else {
    clearFieldError('login-password-err', passwordEl);
    markFieldValid(passwordEl);
  }

  if (!valid) return;
  setButtonLoading('login-btn', true);

  try {
    const user = await apiRequest('/api/auth/login', { email, password });
    openDashboard(user);
  } catch (err) {
    const message = err.message || 'Unable to sign in.';
    setGlobalError('login-global-err', message);
    if (/password/i.test(message)) showFieldError('login-password-err', passwordEl, message);
    else showFieldError('login-email-err', emailEl, message);
  } finally {
    setButtonLoading('login-btn', false);
  }
});

$('login-email')?.addEventListener('blur', function() {
  const val = this.value.trim();
  if (val && !isValidEmail(val)) showFieldError('login-email-err', this, 'Invalid email format.');
  else if (val) {
    clearFieldError('login-email-err', this);
    markFieldValid(this);
  }
});

$('login-password')?.addEventListener('input', function() {
  if (this.value.length > 0) clearFieldError('login-password-err', this);
});

$('reg-password')?.addEventListener('input', function() {
  updateStrengthBar(this.value);
  clearFieldError('reg-password-err', this);
  const confirm = $('reg-confirm').value;
  if (confirm && confirm !== this.value) {
    showFieldError('reg-confirm-err', $('reg-confirm'), 'Passwords do not match.');
  } else if (confirm) {
    clearFieldError('reg-confirm-err', $('reg-confirm'));
    markFieldValid($('reg-confirm'));
  }
});

$('reg-confirm')?.addEventListener('input', function() {
  const password = $('reg-password').value;
  if (this.value && this.value !== password) {
    showFieldError('reg-confirm-err', this, 'Passwords do not match.');
  } else if (this.value) {
    clearFieldError('reg-confirm-err', this);
    markFieldValid(this);
  }
});

$('register-form')?.addEventListener('submit', async e => {
  e.preventDefault();
  setGlobalError('reg-global-err', '');

  const nameEl = $('reg-name');
  const emailEl = $('reg-email');
  const passEl = $('reg-password');
  const confEl = $('reg-confirm');
  const name = nameEl.value.trim();
  const email = emailEl.value.trim();
  const password = passEl.value;
  const confirm = confEl.value;
  let valid = true;

  if (!name) {
    showFieldError('reg-name-err', nameEl, 'Full name is required.');
    valid = false;
  } else if (name.length < 2) {
    showFieldError('reg-name-err', nameEl, 'Name must be at least 2 characters.');
    valid = false;
  } else {
    clearFieldError('reg-name-err', nameEl);
    markFieldValid(nameEl);
  }

  if (!email) {
    showFieldError('reg-email-err', emailEl, 'Email is required.');
    valid = false;
  } else if (!isValidEmail(email)) {
    showFieldError('reg-email-err', emailEl, 'Please enter a valid email address.');
    valid = false;
  } else {
    clearFieldError('reg-email-err', emailEl);
    markFieldValid(emailEl);
  }

  if (!password) {
    showFieldError('reg-password-err', passEl, 'Password is required.');
    valid = false;
  } else if (password.length < 8) {
    showFieldError('reg-password-err', passEl, 'Password must be at least 8 characters.');
    valid = false;
  } else {
    clearFieldError('reg-password-err', passEl);
    markFieldValid(passEl);
  }

  if (!confirm) {
    showFieldError('reg-confirm-err', confEl, 'Please confirm your password.');
    valid = false;
  } else if (confirm !== password) {
    showFieldError('reg-confirm-err', confEl, 'Passwords do not match.');
    valid = false;
  } else {
    clearFieldError('reg-confirm-err', confEl);
    markFieldValid(confEl);
  }

  if (!valid) return;
  setButtonLoading('register-btn', true);

  try {
    const user = await apiRequest('/api/auth/register', { name, email, password });
    openDashboard(user);
  } catch (err) {
    const message = err.message || 'Unable to create account.';
    setGlobalError('reg-global-err', message);
    if (/email|account/i.test(message)) showFieldError('reg-email-err', emailEl, message);
  } finally {
    setButtonLoading('register-btn', false);
  }
});

$('reg-name')?.addEventListener('blur', function() {
  if (!this.value.trim()) showFieldError('reg-name-err', this, 'Full name is required.');
  else {
    clearFieldError('reg-name-err', this);
    markFieldValid(this);
  }
});

$('reg-email')?.addEventListener('blur', function() {
  const val = this.value.trim();
  if (val && !isValidEmail(val)) showFieldError('reg-email-err', this, 'Invalid email format.');
  else if (val) {
    clearFieldError('reg-email-err', this);
    markFieldValid(this);
  }
});

(function redirectIfAlreadyLoggedIn() {
  const stored = sessionStorage.getItem('sp_current_user');
  if (stored) window.location.href = '/dashboard.html';
})();

document.addEventListener('keydown', e => {
  if (e.key === 'Enter' && e.target.classList.contains('tab-btn')) {
    e.target.click();
  }
});

window.switchTab = switchTab;
window.togglePassword = togglePassword;
