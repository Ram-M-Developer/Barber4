/**
 * BarberEase — Complete Admin Dashboard JavaScript
 * Admin-only: Seat management, Waiting Chairs, FIFO Queue, Time Slots
 * Real-time via WebSocket + 5-second fallback polling
 */

let adminSocket = null;
let selectedAdminDate = new Date().toISOString().split('T')[0];
let pendingFinishChairId = null;
let pendingFinishSeatName = '';
let pendingFinishCustomerName = '';
let pendingForceFreeChairId = null;
let pendingQueueAction = null;   // { type, queueId, customerName }
let pendingWaitingChairId = null;
let pollTimer = null;

// ─────────────────────────────────────────────────────────────────────────────
// TOAST NOTIFICATIONS
// ─────────────────────────────────────────────────────────────────────────────

function showAdminToast(message, type = 'danger') {
  const container = document.getElementById('toast-container');
  if (!container) return;

  const iconMap = {
    success: '✅',
    warning: '⚠️',
    danger:  '❌',
    info:    'ℹ️'
  };

  const bgMap = {
    success: 'bg-success text-white',
    warning: 'bg-warning text-dark',
    danger:  'bg-danger text-white',
    info:    'bg-primary text-white'
  };

  const toast = document.createElement('div');
  toast.className = `toast align-items-center ${bgMap[type] || bgMap.danger} border-0 show shadow mb-2`;
  toast.setAttribute('role', 'alert');
  toast.style.minWidth = '290px';
  toast.innerHTML = `
    <div class="d-flex">
      <div class="toast-body small fw-semibold">${iconMap[type] || ''} ${message}</div>
      <button type="button" class="btn-close ${type === 'warning' ? '' : 'btn-close-white'} me-2 m-auto" onclick="this.closest('.toast').remove()"></button>
    </div>
  `;
  container.appendChild(toast);
  setTimeout(() => { toast.classList.remove('show'); setTimeout(() => toast.remove(), 300); }, 5000);
}

// ─────────────────────────────────────────────────────────────────────────────
// AUTHENTICATION CHECK
// ─────────────────────────────────────────────────────────────────────────────

function checkAdminAuth() {
  const token = localStorage.getItem('barber_token');
  const userType = localStorage.getItem('barber_user_type');

  if (!token || userType !== 'admin') {
    window.location.href = '/auth/admin-login.html';
    return false;
  }

  const user = JSON.parse(localStorage.getItem('barber_user') || '{}');
  const el = document.getElementById('admin-username-display');
  if (el) el.textContent = user.full_name || user.username || 'Administrator';

  return true;
}

function updateAdminClock() {
  const now = new Date();
  const el = document.getElementById('admin-date-label');
  if (el) el.textContent = now.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
}

function escapeQuotes(str) {
  if (!str) return '';
  return String(str).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '&quot;');
}

function getToken() {
  return localStorage.getItem('barber_token');
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. LEFT COLUMN: SERVICE SEATS + WAITING CHAIRS
// ─────────────────────────────────────────────────────────────────────────────

async function fetchAdminSeats() {
  const container = document.getElementById('admin-seats-container');
  if (!container) return;

  try {
    const [seatsRes, waitingChairsRes] = await Promise.all([
      fetch('/api/chairs', { headers: { 'Authorization': `Bearer ${getToken()}` } }),
      fetch('/api/waiting-chairs', { headers: { 'Authorization': `Bearer ${getToken()}` } })
    ]);

    if (seatsRes.status === 401 || seatsRes.status === 403) { checkAdminAuth(); return; }

    const seatsJson = await seatsRes.json();
    const chairs = seatsJson.data || [];

    let waitingChairs = [];
    if (waitingChairsRes.ok) {
      const wcJson = await waitingChairsRes.json();
      waitingChairs = wcJson.data || [];
    }

    renderAdminSeats(chairs, waitingChairs);
    updateLeftPanelBadge(chairs, waitingChairs);
  } catch (err) {
    console.error('Error fetching admin seats:', err);
    const sContainer = document.getElementById('admin-service-seats-container');
    if (sContainer) sContainer.innerHTML = `<div class="alert alert-danger p-2 small">Failed to load seats. <button class="btn btn-sm btn-outline-danger ms-2" onclick="fetchAdminSeats()">Retry</button></div>`;
  }
}

function updateLeftPanelBadge(chairs, waitingChairs) {
  const badge = document.getElementById('left-panel-badge');
  if (!badge) return;
  const occupied = chairs.filter(c => c.status === 'occupied').length;
  const wcOccupied = waitingChairs.filter(w => w.status === 'occupied').length;
  badge.textContent = `${occupied}/${chairs.length} Seats · ${wcOccupied}/${waitingChairs.length} Waiting`;
}

function renderAdminSeats(chairs, waitingChairs) {
  const seatsContainer = document.getElementById('admin-service-seats-container');
  const waitingContainer = document.getElementById('admin-waiting-chairs-container');

  if (seatsContainer) {
    let html = '';
    if (chairs.length === 0) {
      html = `<div class="text-center text-muted small py-3">No service seats configured.</div>`;
    } else {
      chairs.forEach(seat => {
        html += renderSeatCard(seat);
      });
    }
    seatsContainer.innerHTML = html;
  }

  if (waitingContainer) {
    let html = '';
    if (waitingChairs.length === 0) {
      html = `<div class="text-center text-muted small py-3">No waiting chairs configured.</div>`;
    } else {
      waitingChairs.forEach(wc => {
        html += renderWaitingChairCard(wc);
      });
    }
    waitingContainer.innerHTML = html;
  }
}

function renderSeatCard(seat) {
  const isOccupied = seat.status === 'occupied' || (seat.customer_name && seat.customer_name !== '---');
  const isCleaning = seat.status === 'cleaning';
  const isOutOfService = seat.status === 'out_of_service' || seat.status === 'maintenance';
  const isReserved = seat.status === 'reserved';

  let statusClass = 'available';
  let statusLabel = 'AVAILABLE';
  let statusColor = 'text-success';

  if (isOccupied) { statusClass = 'occupied'; statusLabel = 'OCCUPIED'; statusColor = 'text-danger'; }
  else if (isCleaning) { statusClass = 'cleaning'; statusLabel = 'CLEANING'; statusColor = 'text-warning'; }
  else if (isOutOfService) { statusClass = 'out-of-service'; statusLabel = 'OUT OF SERVICE'; statusColor = 'text-secondary'; }
  else if (isReserved) { statusClass = 'reserved'; statusLabel = 'RESERVED'; statusColor = 'text-primary'; }

  const customerDisplay = (isOccupied || isReserved) && seat.customer_name && seat.customer_name !== '---'
    ? `<span class="fw-bold text-dark">${seat.customer_name}</span>`
    : `<span class="text-muted fst-italic">Vacant</span>`;

  const serviceDisplay = (isOccupied || isReserved) && seat.service_name && seat.service_name !== '---'
    ? `<span class="fw-semibold text-dark">${seat.service_name}</span>`
    : `<span class="text-muted">—</span>`;

  const timeDisplay = (isOccupied || isReserved) && seat.time && seat.time !== '---'
    ? `<span class="fw-semibold text-primary">${seat.time}</span>`
    : `<span class="text-muted">—</span>`;

  let actionBtns = '';
  if (isOccupied) {
    actionBtns = `
      <div class="d-flex gap-2 mt-3">
        <button class="admin-finish-btn flex-grow-1" onclick="promptFinishService(${seat.id}, 'Seat ${seat.chair_number}', '${escapeQuotes(seat.customer_name)}')">
          <i class="fa-solid fa-check me-1"></i>Complete Service
        </button>
        <button class="btn btn-sm btn-outline-warning fw-bold border-2" title="Force Free Seat"
          onclick="promptForceFreeSeat(${seat.id}, 'Seat ${seat.chair_number}', '${escapeQuotes(seat.customer_name)}')">
          <i class="fa-solid fa-bolt"></i> Force Free
        </button>
      </div>`;
  } else if (isCleaning) {
    actionBtns = `
      <button class="btn btn-sm btn-outline-success w-100 mt-3 fw-bold"
        onclick="updateSeatStatus(${seat.id}, 'available')">
        <i class="fa-solid fa-spray-can me-1"></i>Mark Clean / Available
      </button>`;
  } else if (!isOutOfService) {
    actionBtns = `
      <div class="d-flex gap-2 mt-3">
        <button class="btn btn-sm btn-outline-secondary flex-grow-1" disabled style="opacity:0.5;">Available</button>
        <button class="btn btn-sm btn-outline-secondary" title="Mark out of service"
          onclick="updateSeatStatus(${seat.id}, 'out_of_service')">
          <i class="fa-solid fa-ban"></i>
        </button>
      </div>`;
  } else {
    actionBtns = `
      <button class="btn btn-sm btn-outline-success w-100 mt-3 fw-bold"
        onclick="updateSeatStatus(${seat.id}, 'available')">
        <i class="fa-solid fa-rotate me-1"></i>Restore to Service
      </button>`;
  }

  return `
    <div class="seat-card-compact ${statusClass}">
      <div class="seat-title-row">
        <div class="seat-name"><i class="fa-solid fa-chair me-1"></i>Seat ${seat.chair_number}</div>
        <span class="seat-status-badge ${statusClass}">${statusLabel}</span>
      </div>
      <div class="seat-meta-row mt-2">
        <span class="seat-meta-label">Customer:</span>
        <span class="seat-meta-val">${customerDisplay}</span>
      </div>
      <div class="seat-meta-row">
        <span class="seat-meta-label">Service:</span>
        <span class="seat-meta-val">${serviceDisplay}</span>
      </div>
      <div class="seat-meta-row">
        <span class="seat-meta-label">Since:</span>
        <span class="seat-meta-val">${timeDisplay}</span>
      </div>
      <div class="seat-meta-row">
        <span class="seat-meta-label">Status:</span>
        <span class="seat-meta-val small fw-bold ${statusColor}">${statusLabel}</span>
      </div>
      ${actionBtns}
    </div>`;
}

function renderWaitingChairCard(wc) {
  const isOccupied = wc.status === 'occupied';
  const statusLabel = isOccupied ? 'OCCUPIED' : 'VACANT';

  const customerHtml = isOccupied && wc.customer_name
    ? `<span class="fw-bold text-dark">${wc.customer_name}</span>`
    : `<span class="text-muted fst-italic">Empty</span>`;

  const actionBtns = isOccupied
    ? `<button class="q-btn q-btn-noshow ms-auto" onclick="promptReleaseWaitingChair(${wc.id}, 'W${wc.chair_number}')">
         <i class="fa-solid fa-arrow-up-right-from-square"></i> Release
       </button>`
    : `<button class="q-btn q-btn-seat ms-auto" onclick="promptAssignWaitingChair(${wc.id}, 'W${wc.chair_number}')">
         <i class="fa-solid fa-user-plus"></i> Assign
       </button>`;

  return `
    <div class="waiting-chair-card ${isOccupied ? 'occupied' : 'vacant'}">
      <div class="d-flex align-items-center gap-2 flex-grow-1 min-w-0">
        <span class="wchair-number">W${wc.chair_number}</span>
        <div class="text-truncate flex-grow-1">
          <div style="font-size:0.8rem;">${customerHtml}</div>
        </div>
        <span class="wchair-status-badge ${isOccupied ? 'occupied' : 'vacant'}">${statusLabel}</span>
      </div>
      ${actionBtns}
    </div>`;
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. SEAT SERVICE ACTIONS
// ─────────────────────────────────────────────────────────────────────────────

function promptFinishService(chairId, seatName, customerName) {
  pendingFinishChairId = chairId;
  pendingFinishSeatName = seatName;
  pendingFinishCustomerName = customerName;
  document.getElementById('finish-customer-name').textContent = customerName || 'Current Customer';
  document.getElementById('finish-seat-name').textContent = seatName;
  bootstrap.Modal.getOrCreateInstance(document.getElementById('finishServiceModal')).show();
}

async function executeFinishService() {
  if (!pendingFinishChairId) return;
  const btn = document.getElementById('btn-confirm-finish');
  if (btn) { btn.disabled = true; btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Finishing…'; }

  try {
    const res = await fetch(`/api/chairs/${pendingFinishChairId}/complete-service`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' }
    });
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to complete service');

    bootstrap.Modal.getInstance(document.getElementById('finishServiceModal'))?.hide();
    showAdminToast(`Service completed for ${pendingFinishSeatName}. Queue updated automatically!`, 'success');
    refreshAllAdminPanels();
  } catch (err) {
    showAdminToast(err.message || 'Error completing service', 'danger');
  } finally {
    if (btn) { btn.disabled = false; btn.innerHTML = '<i class="fa-solid fa-check me-1"></i>Complete Service'; }
  }
}

function promptForceFreeSeat(chairId, seatName, customerName) {
  pendingForceFreeChairId = chairId;
  document.getElementById('force-free-customer-name').textContent = customerName || 'Current Customer';
  document.getElementById('force-free-seat-name').textContent = seatName;
  document.getElementById('forceFreeReason').value = '';
  bootstrap.Modal.getOrCreateInstance(document.getElementById('forceFreeModal')).show();
}

async function executeForceFreeSeat() {
  if (!pendingForceFreeChairId) return;
  const reason = document.getElementById('forceFreeReason').value.trim();
  if (!reason) { showAdminToast('Please provide a reason for force freeing the seat.', 'warning'); return; }

  const btn = document.getElementById('btn-confirm-force-free');
  if (btn) { btn.disabled = true; btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Processing…'; }

  try {
    const res = await fetch(`/api/chairs/${pendingForceFreeChairId}/force-free`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason })
    });
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to force free seat');

    bootstrap.Modal.getInstance(document.getElementById('forceFreeModal'))?.hide();
    showAdminToast('Seat force freed. Service marked INTERRUPTED. Queue updated.', 'warning');
    refreshAllAdminPanels();
  } catch (err) {
    showAdminToast(err.message || 'Error force freeing seat', 'danger');
  } finally {
    if (btn) { btn.disabled = false; btn.innerHTML = '<i class="fa-solid fa-bolt me-1"></i>Force Free Seat'; }
  }
}

async function updateSeatStatus(chairId, newStatus) {
  try {
    const res = await fetch(`/api/chairs/${chairId}/status`, {
      method: 'PUT',
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: newStatus })
    });
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to update seat status');
    showAdminToast(`Seat status updated to ${newStatus.toUpperCase()}.`, 'success');
    refreshAllAdminPanels();
  } catch (err) {
    showAdminToast(err.message || 'Error updating seat status', 'danger');
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. WAITING CHAIR ACTIONS
// ─────────────────────────────────────────────────────────────────────────────

function promptAssignWaitingChair(chairId, chairName) {
  pendingWaitingChairId = chairId;
  document.getElementById('awc-chair-name').textContent = `Waiting Chair ${chairName}`;
  document.getElementById('awc-customer-id').value = '';
  bootstrap.Modal.getOrCreateInstance(document.getElementById('assignWaitingChairModal')).show();
}

async function executeAssignWaitingChair() {
  const customerId = parseInt(document.getElementById('awc-customer-id').value);
  if (!customerId || isNaN(customerId) || customerId < 1) {
    showAdminToast('Please enter a valid customer ID.', 'warning');
    return;
  }
  try {
    const res = await fetch(`/api/waiting-chairs/${pendingWaitingChairId}/assign`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ customer_id: customerId })
    });
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to assign waiting chair');

    bootstrap.Modal.getInstance(document.getElementById('assignWaitingChairModal'))?.hide();
    showAdminToast('Customer assigned to waiting chair.', 'success');
    fetchAdminSeats();
  } catch (err) {
    showAdminToast(err.message || 'Error assigning waiting chair', 'danger');
  }
}

async function promptReleaseWaitingChair(chairId, chairName) {
  if (!confirm(`Release waiting chair ${chairName}? The customer will vacate this chair.`)) return;
  try {
    const res = await fetch(`/api/waiting-chairs/${chairId}/release`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: 'Released by admin' })
    });
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to release waiting chair');
    showAdminToast(`Waiting chair ${chairName} released.`, 'success');
    fetchAdminSeats();
  } catch (err) {
    showAdminToast(err.message || 'Error releasing waiting chair', 'danger');
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. CENTER COLUMN: TIME SLOTS
// ─────────────────────────────────────────────────────────────────────────────

function selectAdminDateFilter(period) {
  document.querySelectorAll('.slot-date-btn').forEach(b => b.classList.remove('active'));
  const today = new Date();
  const target = new Date();
  if (period === 'today') {
    document.getElementById('admin-btn-date-today')?.classList.add('active');
  } else if (period === 'tomorrow') {
    target.setDate(today.getDate() + 1);
    document.getElementById('admin-btn-date-tomorrow')?.classList.add('active');
  } else if (period === 'dayafter') {
    target.setDate(today.getDate() + 2);
    document.getElementById('admin-btn-date-dayafter')?.classList.add('active');
  }
  selectedAdminDate = target.toISOString().split('T')[0];
  const di = document.getElementById('admin-slot-picker-date');
  if (di) di.value = selectedAdminDate;
  fetchAdminSlots();
}

function onAdminDateChanged(val) {
  if (!val) return;
  selectedAdminDate = val;
  document.querySelectorAll('.slot-date-btn').forEach(b => b.classList.remove('active'));
  fetchAdminSlots();
}

async function fetchAdminSlots() {
  const container = document.getElementById('admin-slots-list');
  if (!container) return;
  try {
    const res = await fetch(`/api/appointments/slots/detailed?date=${selectedAdminDate}`, {
      headers: { 'Authorization': `Bearer ${getToken()}` }
    });
    if (res.status === 401 || res.status === 403) { checkAdminAuth(); return; }
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to fetch slots');
    renderAdminSlots(json.data || []);
  } catch (err) {
    container.innerHTML = `<div class="alert alert-danger p-2 small">Failed to load slots. <button class="btn btn-sm btn-outline-danger ms-2" onclick="fetchAdminSlots()">Retry</button></div>`;
  }
}

function renderAdminSlots(slots) {
  const container = document.getElementById('admin-slots-list');
  if (!container) return;

  if (!slots || slots.length === 0) {
    container.innerHTML = `
      <div class="text-center py-5 text-muted small">
        <i class="fa-solid fa-calendar-xmark fa-2x mb-2 opacity-30"></i><br>
        No time slots available for the selected date.
      </div>`;
    return;
  }

  let html = '';
  slots.forEach(slot => {
    const capacity = slot.capacity || 2;
    const assigned = slot.assignedCount || 0;
    const isFull = assigned >= capacity;

    let badgePill = '';
    const available = capacity - assigned;
    if (assigned === 0) {
      badgePill = `<span class="slot-badge-pill available-2">0 / ${capacity} — AVAILABLE</span>`;
    } else if (assigned < capacity) {
      badgePill = `<span class="slot-badge-pill available-1">${assigned} / ${capacity} — ${available} SEAT${available !== 1 ? 'S' : ''} AVAILABLE</span>`;
    } else {
      badgePill = `<span class="slot-badge-pill full">${assigned} / ${capacity} — FULL</span>`;
    }

    let customersHtml = '';
    if (slot.bookedCustomers && slot.bookedCustomers.length > 0) {
      customersHtml = `
        <div class="slot-customers-box mt-1">
          ${slot.bookedCustomers.map(c => `
            <span class="slot-booking-customer-chip">
              <i class="fa-solid fa-user fa-xs me-1"></i>
              <strong>${c.customerName || 'Customer'}</strong>
              <span class="text-muted ms-1">(${c.chairNumber ? 'Seat ' + c.chairNumber : (c.tokenNumber || 'Queued')})</span>
            </span>
          `).join('')}
        </div>`;
    } else {
      customersHtml = `<div class="small text-muted mt-1 fst-italic" style="font-size:0.75rem;"><i class="fa-regular fa-calendar me-1"></i>Seat${capacity - assigned !== 1 ? 's' : ''} available — no reservations yet</div>`;
    }

    html += `
      <div class="slot-capacity-card ${isFull ? 'full' : ''} flex-column align-items-stretch">
        <div class="d-flex align-items-center justify-content-between">
          <div class="slot-time-title">${slot.timeSlot}</div>
          <div>${badgePill}</div>
        </div>
        ${customersHtml}
      </div>`;
  });

  container.innerHTML = html;
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. RIGHT COLUMN: FIFO WAITING LIST (ADMIN — full detail)
// ─────────────────────────────────────────────────────────────────────────────

async function fetchAdminQueue() {
  const container = document.getElementById('admin-waiting-queue-list');
  const countEl = document.getElementById('admin-waiting-queue-count');
  if (!container) return;

  try {
    const res = await fetch('/api/queue/waiting', {
      headers: { 'Authorization': `Bearer ${getToken()}` }
    });
    if (res.status === 401 || res.status === 403) { checkAdminAuth(); return; }
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to fetch queue');

    const list = json.data || [];
    if (countEl) countEl.textContent = json.count !== undefined ? json.count : list.length;
    renderAdminQueue(list);
  } catch (err) {
    container.innerHTML = `<div class="alert alert-danger p-2 small">Failed to load queue. <button class="btn btn-sm btn-outline-danger ms-2" onclick="fetchAdminQueue()">Retry</button></div>`;
  }
}

function renderAdminQueue(list) {
  const container = document.getElementById('admin-waiting-queue-list');
  if (!container) return;

  if (!list || list.length === 0) {
    container.innerHTML = `
      <div class="empty-queue-msg">
        <div class="icon">🪑</div>
        <div class="fw-bold text-dark mb-1">No customers waiting</div>
        <div class="text-muted small">New eligible bookings can be assigned directly when a suitable seat is available.</div>
      </div>`;
    return;
  }

  let html = `
    <div class="d-flex justify-content-between align-items-center mb-2 px-1">
      <span class="text-muted small fw-semibold">${list.length} customer(s) in FIFO queue</span>
    </div>`;

  list.forEach((item, index) => {
    const rank = item.position || (index + 1);
    const customerName = item.customer_name || 'Customer';
    const serviceName = item.service_name || 'Service';
    const tokenNum = item.token_number || ('Q-' + rank);
    const queueId = item.id;
    const estimatedWait = item.estimated_wait_minutes > 0 ? `~${item.estimated_wait_minutes} min` : 'Next';
    const joinedAt = item.joined_at ? new Date(item.joined_at).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) : '';
    const requestedSlot = item.requested_slot || 'Next Available';
    const assignedSeat = item.assigned_seat || null;

    html += `
      <div class="queue-admin-entry">
        <div class="d-flex align-items-start gap-2">
          <div class="queue-position-badge">#${rank}</div>
          <div class="flex-grow-1 min-w-0">
            <div class="fw-bold text-dark" style="font-size:0.9rem;">${customerName}</div>
            <div class="text-muted" style="font-size:0.73rem;">
              <span class="text-primary fw-semibold">${tokenNum}</span>
              · ${serviceName}
              ${joinedAt ? `· Joined: ${joinedAt}` : ''}
            </div>
            <div class="d-flex gap-2 mt-1 flex-wrap align-items-center">
              <span class="badge bg-warning-subtle text-warning-emphasis border border-warning-subtle fw-semibold" style="font-size:0.68rem;">WAITING</span>
              <span class="text-muted" style="font-size:0.7rem;"><i class="fa-regular fa-clock me-1"></i>${estimatedWait}</span>
              ${assignedSeat ? `<span class="badge bg-success-subtle text-success-emphasis border border-success-subtle" style="font-size:0.68rem;">→ ${assignedSeat}</span>` : ''}
              <span class="text-muted" style="font-size:0.7rem;">Slot: ${requestedSlot}</span>
            </div>
          </div>
        </div>
        <div class="queue-admin-actions">
          <button class="q-btn q-btn-call" title="Call customer to service seat"
            onclick="callQueueCustomer(${queueId}, '${escapeQuotes(customerName)}')">
            <i class="fa-solid fa-bullhorn me-1"></i>Call
          </button>
          <button class="q-btn q-btn-seat" title="Assign customer to available seat"
            onclick="seatQueueCustomer(${queueId}, '${escapeQuotes(customerName)}')">
            <i class="fa-solid fa-chair me-1"></i>Assign Seat
          </button>
          <button class="q-btn q-btn-skip" title="Skip this customer"
            onclick="promptQueueAction('skip', ${queueId}, '${escapeQuotes(customerName)}')">
            <i class="fa-solid fa-forward-step me-1"></i>Skip
          </button>
          <button class="q-btn q-btn-noshow" title="Mark as No-Show"
            onclick="promptQueueAction('no-show', ${queueId}, '${escapeQuotes(customerName)}')">
            <i class="fa-solid fa-user-slash me-1"></i>No-Show
          </button>
          <button class="q-btn q-btn-remove" title="Remove from queue"
            onclick="promptQueueAction('remove', ${queueId}, '${escapeQuotes(customerName)}')">
            <i class="fa-solid fa-xmark me-1"></i>Remove
          </button>
        </div>
      </div>`;
  });

  container.innerHTML = html;
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. QUEUE ACTIONS
// ─────────────────────────────────────────────────────────────────────────────

async function executeAssignNext() {
  try {
    const res = await fetch('/api/queue/call-next', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' }
    });
    const json = await res.json();
    if (!res.ok) {
      showAdminToast(json.message || 'No waiting customers or no available seats.', 'warning');
      return;
    }
    showAdminToast('Next waiting customer assigned to available seat!', 'success');
    refreshAllAdminPanels();
  } catch (err) {
    showAdminToast(err.message || 'Error assigning next customer', 'danger');
  }
}

async function callQueueCustomer(queueId, customerName) {
  try {
    const res = await fetch(`/api/queue/${queueId}/call`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' }
    });
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to call customer');
    showAdminToast(`${customerName} called to the service station!`, 'success');
    refreshAllAdminPanels();
  } catch (err) {
    showAdminToast(err.message || 'Error calling customer', 'danger');
  }
}

async function seatQueueCustomer(queueId, customerName) {
  try {
    const res = await fetch(`/api/queue/${queueId}/seat`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' }
    });
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || 'Failed to seat customer');
    showAdminToast(`${customerName} seated. Service started!`, 'success');
    refreshAllAdminPanels();
  } catch (err) {
    showAdminToast(err.message || 'Error seating customer', 'danger');
  }
}

// Queue action modal (skip / no-show / remove)
function promptQueueAction(type, queueId, customerName) {
  pendingQueueAction = { type, queueId, customerName };

  const titleMap = { skip: 'Skip Customer', 'no-show': 'Mark No-Show', remove: 'Remove from Queue' };
  const descMap = {
    skip: 'Skip this customer from the queue? They remain in history as skipped.',
    'no-show': 'Mark this customer as no-show? They will be removed from the active queue.',
    remove: 'Remove this customer from the queue? Their queue entry will be cancelled.'
  };
  const btnClassMap = { skip: 'btn-secondary', 'no-show': 'btn-danger', remove: 'btn-dark' };
  const btnLabelMap = { skip: '⏭ Skip', 'no-show': '🚫 Mark No-Show', remove: '✖ Remove' };

  document.getElementById('queueActionModalTitle').textContent = titleMap[type];
  document.getElementById('queueActionModalDesc').textContent = descMap[type];
  document.getElementById('queueActionModalCustomer').textContent = customerName;
  document.getElementById('queueActionReason').value = '';

  const btn = document.getElementById('btn-confirm-queue-action');
  btn.className = `btn btn-sm fw-bold ${btnClassMap[type]}`;
  btn.textContent = btnLabelMap[type];

  bootstrap.Modal.getOrCreateInstance(document.getElementById('queueActionModal')).show();
}

async function executeQueueAction() {
  if (!pendingQueueAction) return;
  const { type, queueId, customerName } = pendingQueueAction;
  const reason = document.getElementById('queueActionReason').value.trim();

  const endpointMap = { skip: 'skip', 'no-show': 'no-show', remove: 'cancel' };
  const methodMap = { skip: 'POST', 'no-show': 'POST', remove: 'PUT' };
  const endpoint = endpointMap[type];
  const method = methodMap[type];

  const btn = document.getElementById('btn-confirm-queue-action');
  if (btn) { btn.disabled = true; btn.innerHTML = '<span class="spinner-border spinner-border-sm"></span>'; }

  try {
    const res = await fetch(`/api/queue/${queueId}/${endpoint}`, {
      method,
      headers: { 'Authorization': `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason: reason || undefined })
    });
    const json = await res.json();
    if (!res.ok) throw new Error(json.message || `Failed to ${type} customer`);

    bootstrap.Modal.getInstance(document.getElementById('queueActionModal'))?.hide();
    showAdminToast(`${customerName}: ${type} action completed. Queue updated.`, type === 'remove' ? 'success' : 'warning');
    refreshAllAdminPanels();
  } catch (err) {
    showAdminToast(err.message || `Error: ${type} action failed`, 'danger');
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = 'Confirm'; }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 7. REAL-TIME SYNC: WEBSOCKET + POLLING FALLBACK
// ─────────────────────────────────────────────────────────────────────────────

function refreshAllAdminPanels() {
  fetchAdminSeats();
  fetchAdminSlots();
  fetchAdminQueue();
}

function setupAdminWebSocket() {
  try {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    adminSocket = new WebSocket(`${protocol}//${window.location.host}/ws`);

    adminSocket.onopen = () => console.log('✅ Admin WebSocket connected.');

    adminSocket.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        if (['chair-update', 'queue-update', 'slot-update', 'appointment-update', 'waiting-chair-update'].includes(data.event)) {
          refreshAllAdminPanels();
        }
      } catch (e) {
        console.error('WS parse error:', e);
      }
    };

    adminSocket.onerror = (err) => console.warn('Admin WS error:', err);

    adminSocket.onclose = () => {
      console.warn('Admin WS closed — reconnecting in 3s...');
      setTimeout(setupAdminWebSocket, 3000);
    };
  } catch (err) {
    console.warn('Failed to init WebSocket:', err);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. INITIALIZATION
// ─────────────────────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  if (!checkAdminAuth()) return;

  // Initialize date picker
  const di = document.getElementById('admin-slot-picker-date');
  if (di) { di.value = selectedAdminDate; di.min = selectedAdminDate; }

  updateAdminClock();
  refreshAllAdminPanels();
  setupAdminWebSocket();

  // Fallback polling every 8 seconds for reliability
  pollTimer = setInterval(refreshAllAdminPanels, 8000);
});
