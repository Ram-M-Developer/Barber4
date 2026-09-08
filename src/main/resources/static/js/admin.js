/**
 * Beauty Salon — Complete Real-Time Admin Panel
 * Manages 2 Service Seats, Time Slots / Bookings, and Strict FIFO Waiting Queue
 */

let adminSocket = null;
let selectedAdminDate = new Date().toISOString().split('T')[0];
let pendingFinishChairId = null;
let pendingFinishSeatName = '';
let pendingFinishCustomerName = '';
let pollTimer = null;

// Show Admin Toast Notification (No icons)
function showAdminToast(message, type = 'danger') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const toast = document.createElement('div');
  const bgClass = type === 'success' ? 'bg-success' : (type === 'warning' ? 'bg-warning text-dark' : 'bg-danger');
  toast.className = `toast align-items-center text-white ${bgClass} border-0 show shadow-sm mb-2`;
  toast.setAttribute('role', 'alert');
  toast.style.minWidth = '280px';

  toast.innerHTML = `
    <div class="d-flex">
      <div class="toast-body small fw-semibold">
        ${message}
      </div>
      <button type="button" class="btn-close ${type === 'warning' ? '' : 'btn-close-white'} me-2 m-auto" data-bs-dismiss="toast" aria-label="Close"></button>
    </div>
  `;

  container.appendChild(toast);
  setTimeout(() => {
    toast.classList.remove('show');
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}

// Check Admin Authorization
function checkAdminAuth() {
  const token = localStorage.getItem('barber_token');
  const userType = localStorage.getItem('barber_user_type');

  if (!token || userType !== 'admin') {
    window.location.href = '/auth/admin-login.html';
    return false;
  }

  const user = JSON.parse(localStorage.getItem('barber_user') || '{}');
  const adminNameDisplay = document.getElementById('admin-username-display');
  if (adminNameDisplay) {
    adminNameDisplay.textContent = user.full_name || user.username || 'Administrator';
  }

  return true;
}

// Format local date for header
function updateAdminClock() {
  const now = new Date();
  const dateStr = now.toLocaleDateString([], { month: 'short', day: 'numeric', year: 'numeric' });
  const dateEl = document.getElementById('admin-date-label');
  if (dateEl) dateEl.textContent = dateStr;
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. LEFT COLUMN: 2 SERVICE SEATS (Matches Customer Layout)
// ─────────────────────────────────────────────────────────────────────────────

async function fetchAdminSeats() {
  const token = localStorage.getItem('barber_token');
  const container = document.getElementById('admin-seats-container');
  if (!container) return;

  try {
    const res = await fetch('/api/chairs', {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (res.status === 401 || res.status === 403) {
      checkAdminAuth();
      return;
    }

    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to fetch seats');

    const chairs = json.data || [];
    renderAdminSeats(chairs);
  } catch (err) {
    console.error('Error fetching admin seats:', err);
    container.innerHTML = `
      <div class="alert alert-danger p-2 small">
        Failed to load seats.
      </div>
    `;
  }
}

function renderAdminSeats(chairs) {
  const container = document.getElementById('admin-seats-container');
  if (!container) return;

  // Filter or ensure exactly 2 seats (Seat 1 and Seat 2)
  let seat1 = chairs.find(c => c.chair_number === 1) || { chair_number: 1, name: 'Seat 1', status: 'available', customer_name: '---', time: '---' };
  let seat2 = chairs.find(c => c.chair_number === 2) || { chair_number: 2, name: 'Seat 2', status: 'available', customer_name: '---', time: '---' };

  const seats = [seat1, seat2];

  let html = '';
  seats.forEach(seat => {
    const isOccupied = seat.status === 'occupied' || (seat.customer_name && seat.customer_name !== '---');
    const statusClass = isOccupied ? 'occupied' : 'available';
    const statusBadge = isOccupied ? 'CURRENTLY SERVING' : 'AVAILABLE';

    const customerDisplay = isOccupied
      ? `<span class="fw-bold text-dark fs-6">${seat.customer_name}</span>`
      : `<span class="text-muted fst-italic">---</span>`;

    const timeDisplay = isOccupied && seat.time && seat.time !== '---'
      ? `<span class="fw-semibold text-primary">${seat.time}</span>`
      : `<span class="text-muted">---</span>`;

    const finishBtn = isOccupied
      ? `
        <button class="admin-finish-btn mt-3" onclick="promptFinishService(${seat.id || seat.chair_number}, 'Seat ${seat.chair_number}', '${escapeQuotes(seat.customer_name)}')">
          Finish Service
        </button>
      `
      : `
        <button class="admin-finish-btn mt-3" disabled style="opacity:0.65;">
          Available
        </button>
      `;

    html += `
      <div class="seat-card-compact ${statusClass}">
        <div class="seat-title-row">
          <div class="seat-name">
            Seat ${seat.chair_number}
          </div>
          <span class="seat-status-badge ${statusClass}">${statusBadge}</span>
        </div>

        <div class="seat-meta-row mt-2">
          <span class="seat-meta-label">Customer:</span>
          <span class="seat-meta-val">${customerDisplay}</span>
        </div>

        <div class="seat-meta-row">
          <span class="seat-meta-label">Time:</span>
          <span class="seat-meta-val">${timeDisplay}</span>
        </div>

        <div class="seat-meta-row">
          <span class="seat-meta-label">Status:</span>
          <span class="seat-meta-val small fw-bold ${isOccupied ? 'text-danger' : 'text-success'}">
            ${isOccupied ? 'CURRENTLY SERVING' : 'AVAILABLE'}
          </span>
        </div>

        ${finishBtn}
      </div>
    `;
  });

  container.innerHTML = html;
}

function escapeQuotes(str) {
  if (!str) return '';
  return str.replace(/'/g, "\\'").replace(/"/g, '&quot;');
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. FINISH SERVICE ACTION (ATOMIC AUTO-HANDOVER)
// ─────────────────────────────────────────────────────────────────────────────

function promptFinishService(chairId, seatName, customerName) {
  pendingFinishChairId = chairId;
  pendingFinishSeatName = seatName;
  pendingFinishCustomerName = customerName;

  document.getElementById('finish-customer-name').textContent = customerName || 'Current Customer';
  document.getElementById('finish-seat-name').textContent = seatName;

  const modalEl = document.getElementById('finishServiceModal');
  const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
  modal.show();
}

async function executeFinishService() {
  if (!pendingFinishChairId) return;

  const btn = document.getElementById('btn-confirm-finish');
  if (btn) {
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Finishing…';
  }

  const token = localStorage.getItem('barber_token');

  try {
    const res = await fetch(`/api/chairs/${pendingFinishChairId}/complete-service`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      }
    });

    const json = await res.json();

    if (!res.ok) {
      throw new Error(json.message || 'Failed to complete service');
    }

    // Close modal
    const modalEl = document.getElementById('finishServiceModal');
    const modal = bootstrap.Modal.getInstance(modalEl);
    if (modal) modal.hide();

    showAdminToast(`Service finished for ${pendingFinishSeatName}. Queue updated!`, 'success');

    // Refresh all panels immediately
    refreshAllAdminPanels();

  } catch (err) {
    console.error('Error completing service:', err);
    showAdminToast(err.message || 'Error completing service', 'danger');
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.innerHTML = 'Complete Service';
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. CENTER COLUMN: TIME SLOTS & BOOKINGS
// ─────────────────────────────────────────────────────────────────────────────

function selectAdminDateFilter(period) {
  document.querySelectorAll('.slot-date-btn').forEach(b => b.classList.remove('active'));

  const today = new Date();
  let target = new Date();

  if (period === 'today') {
    document.getElementById('admin-btn-date-today').classList.add('active');
  } else if (period === 'tomorrow') {
    target.setDate(today.getDate() + 1);
    document.getElementById('admin-btn-date-tomorrow').classList.add('active');
  } else if (period === 'dayafter') {
    target.setDate(today.getDate() + 2);
    document.getElementById('admin-btn-date-dayafter').classList.add('active');
  }

  selectedAdminDate = target.toISOString().split('T')[0];
  const dateInput = document.getElementById('admin-slot-picker-date');
  if (dateInput) dateInput.value = selectedAdminDate;

  fetchAdminSlots();
}

function onAdminDateChanged(val) {
  if (!val) return;
  selectedAdminDate = val;
  document.querySelectorAll('.slot-date-btn').forEach(b => b.classList.remove('active'));
  fetchAdminSlots();
}

async function fetchAdminSlots() {
  const token = localStorage.getItem('barber_token');
  const container = document.getElementById('admin-slots-list');
  if (!container) return;

  try {
    const res = await fetch(`/api/appointments/slots/detailed?date=${selectedAdminDate}`, {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (res.status === 401 || res.status === 403) {
      checkAdminAuth();
      return;
    }

    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to fetch slots');

    renderAdminSlots(json.data || []);
  } catch (err) {
    console.error('Error fetching admin slots:', err);
    container.innerHTML = `
      <div class="alert alert-danger p-2 small">
        Failed to load time slots.
      </div>
    `;
  }
}

function renderAdminSlots(slots) {
  const container = document.getElementById('admin-slots-list');
  if (!container) return;

  if (!slots || slots.length === 0) {
    container.innerHTML = `
      <div class="text-center py-5 text-muted small">
        No available time slots remaining for the selected date.
      </div>
    `;
    return;
  }

  let html = '';
  slots.forEach(slot => {
    const capacity = slot.capacity || 2;
    const assigned = slot.assignedCount || 0;
    const isFull = assigned >= capacity;

    let badgePill = '';
    if (assigned === 0) {
      badgePill = `<span class="slot-badge-pill available-2">0 / ${capacity} AVAILABLE</span>`;
    } else if (assigned < capacity) {
      badgePill = `<span class="slot-badge-pill available-1">${assigned} / ${capacity} AVAILABLE</span>`;
    } else {
      badgePill = `<span class="slot-badge-pill full">${assigned} / ${capacity} FULL</span>`;
    }

    // Booked customers info for this slot
    let customersHtml = '';
    if (slot.bookedCustomers && slot.bookedCustomers.length > 0) {
      customersHtml = `
        <div class="slot-customers-box">
          ${slot.bookedCustomers.map(c => `
            <span class="slot-booking-customer-chip">
              <strong>${c.customerName || 'Customer'}</strong>
              <span class="text-muted">(${c.chairNumber ? 'Seat ' + c.chairNumber : (c.tokenNumber || 'Booked')})</span>
            </span>
          `).join('')}
        </div>
      `;
    } else {
      customersHtml = `
        <div class="small text-muted mt-1 fst-italic" style="font-size:0.75rem;">
          No bookings yet for this slot
        </div>
      `;
    }

    html += `
      <div class="slot-capacity-card ${isFull ? 'full' : ''} flex-column align-items-stretch">
        <div class="d-flex align-items-center justify-content-between">
          <div class="slot-time-title">
            ${slot.timeSlot}
          </div>
          <div>${badgePill}</div>
        </div>
        ${customersHtml}
      </div>
    `;
  });

  container.innerHTML = html;
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. RIGHT COLUMN: FIFO WAITING LIST
// ─────────────────────────────────────────────────────────────────────────────

async function fetchAdminQueue() {
  const token = localStorage.getItem('barber_token');
  const container = document.getElementById('admin-waiting-queue-list');
  const countEl = document.getElementById('admin-waiting-queue-count');
  if (!container) return;

  try {
    const res = await fetch('/api/queue/waiting', {
      headers: { 'Authorization': `Bearer ${token}` }
    });

    if (res.status === 401 || res.status === 403) {
      checkAdminAuth();
      return;
    }

    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to fetch queue');

    const list = json.data || [];
    if (countEl) countEl.textContent = json.count !== undefined ? json.count : list.length;

    renderAdminQueue(list);
  } catch (err) {
    console.error('Error fetching admin queue:', err);
    container.innerHTML = `
      <div class="alert alert-danger p-2 small">
        Failed to load waiting list.
      </div>
    `;
  }
}

function renderAdminQueue(list) {
  const container = document.getElementById('admin-waiting-queue-list');
  if (!container) return;

  if (!list || list.length === 0) {
    container.innerHTML = `
      <div class="text-center py-5 text-muted small">
        Queue is currently empty.<br>
        <span class="text-secondary" style="font-size:0.75rem;">Next customer who books a full slot will queue here.</span>
      </div>
    `;
    return;
  }

  let html = '';
  list.forEach((item, index) => {
    const rank = item.position || (index + 1);
    const customerName = item.customer_name || 'Customer';
    const requestedSlot = item.requested_slot || 'Next Available';
    const token = item.token_number || ('Q-' + rank);

    html += `
      <div class="queue-list-entry">
        <div class="d-flex align-items-center min-w-0">
          <div class="queue-rank-badge">#${rank}</div>
          <div class="text-truncate">
            <div class="fw-bold text-dark text-truncate" style="font-size:0.88rem;">${customerName}</div>
            <div class="text-muted" style="font-size:0.73rem;">
              <span class="text-primary fw-semibold">${token}</span> · Slot: ${requestedSlot}
            </div>
          </div>
        </div>
        <div class="ms-2 flex-shrink-0">
          <span class="badge bg-warning-subtle text-warning-emphasis border border-warning-subtle fw-semibold" style="font-size:0.7rem;">
            WAITING
          </span>
        </div>
      </div>
    `;
  });

  container.innerHTML = html;
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. REAL-TIME SYNCHRONIZATION & WEBSOCKET
// ─────────────────────────────────────────────────────────────────────────────

function refreshAllAdminPanels() {
  fetchAdminSeats();
  fetchAdminSlots();
  fetchAdminQueue();
}

function setupAdminSockets() {
  try {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    adminSocket = new WebSocket(`${protocol}//${window.location.host}/ws`);

    adminSocket.onopen = () => {
      console.log('Admin WebSocket connected.');
    };

    adminSocket.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (['chair-update', 'queue-update', 'slot-update', 'appointment-update'].includes(data.event)) {
          refreshAllAdminPanels();
        }
      } catch (e) {
        console.error('Error parsing socket message:', e);
      }
    };

    adminSocket.onerror = (err) => {
      console.warn('Admin WebSocket error:', err);
    };

    adminSocket.onclose = () => {
      setTimeout(setupAdminSockets, 3000);
    };

  } catch (err) {
    console.warn('Failed to initialize WebSocket:', err);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. INITIALIZATION & LIFECYCLE
// ─────────────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  // Verify Admin Authentication
  if (!checkAdminAuth()) return;

  // Initialize date picker input
  const dateInput = document.getElementById('admin-slot-picker-date');
  if (dateInput) {
    dateInput.value = selectedAdminDate;
    dateInput.min = selectedAdminDate;
  }

  // Real-time Date
  updateAdminClock();

  // Initial Data Load
  refreshAllAdminPanels();

  // Connect WebSocket
  setupAdminSockets();

  // Fallback Polling every 10 seconds to guarantee synchronization
  pollTimer = setInterval(refreshAllAdminPanels, 10000);
});
