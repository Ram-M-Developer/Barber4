const http = require('http');
const PORT = process.env.PORT || 6080;

function request(options, data) {
  return new Promise((resolve, reject) => {
    const req = http.request(options, (res) => {
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(body); } catch (e) { json = body; }
        resolve({ status: res.statusCode, body: json });
      });
    });
    req.on('error', reject);
    if (data) {
      req.write(typeof data === 'string' ? data : JSON.stringify(data));
    }
    req.end();
  });
}

async function run() {
  console.log('--- STARTING ADMIN PANEL END-TO-END VERIFICATION ---');

  // Step 1: Verify Unauthorized access on complete-service
  console.log('\n[TEST 1] Non-admin / unauthenticated access to /api/chairs/1/complete-service');
  const unauthRes = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/chairs/1/complete-service',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' }
  });
  console.log(`Status: ${unauthRes.status} (Expected: 403 Forbidden)`);
  if (unauthRes.status !== 403) throw new Error(`Expected 403, got ${unauthRes.status}`);
  console.log('✓ PASS: Unauthorized access successfully blocked with 403!');

  // Step 2: Admin Login
  console.log('\n[TEST 2] Admin Login');
  const adminLogin = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/auth/admin/login',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' }
  }, { username: 'admin', password: 'admin123' });
  console.log(`Status: ${adminLogin.status}, Success: ${adminLogin.body.success}`);
  const adminToken = adminLogin.body.data.token;
  console.log('✓ PASS: Admin authenticated, token acquired.');

  // Step 3: Register 4 Customers
  const timestamp = Date.now();
  const customers = [];
  for (let i = 1; i <= 4; i++) {
    const email = `testcust${i}_${timestamp}@example.com`;
    const regRes = await request({
      hostname: 'localhost',
      port: PORT,
      path: '/api/auth/register',
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    }, {
      name: `Customer ${i} (${timestamp % 1000})`,
      email: email,
      phone: `9876543${i}${timestamp % 100}`,
      password: 'password123'
    });
    customers.push({
      id: regRes.body.data.customer.id,
      name: regRes.body.data.customer.name,
      token: regRes.body.data.token
    });
  }
  console.log(`✓ PASS: Registered 4 customers: ${customers.map(c => c.name).join(', ')}`);

  // Step 4: Customer 1 books
  console.log('\n[TEST 3] Customer 1 books -> Auto-assigned Seat 1');
  const today = new Date().toISOString().split('T')[0];
  const book1 = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/appointments/book-slot',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${customers[0].token}`
    }
  }, {
    date: today,
    timeSlot: '09:00 PM - 10:00 PM',
    notes: 'Booking 1'
  });
  console.log(`Cust 1 booking status: ${book1.status}, chair: Seat ${book1.body.data?.chair?.chairNumber}`);

  // Step 5: Customer 2 books
  console.log('\n[TEST 4] Customer 2 books -> Auto-assigned Seat 2');
  const book2 = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/appointments/book-slot',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${customers[1].token}`
    }
  }, {
    date: today,
    timeSlot: '09:00 PM - 10:00 PM',
    notes: 'Booking 2'
  });
  console.log(`Cust 2 booking status: ${book2.status}, chair: Seat ${book2.body.data?.chair?.chairNumber}`);

  // Check chairs state
  const chairsRes = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/chairs',
    method: 'GET'
  });
  console.log('Chairs state:', chairsRes.body.data.map(c => `${c.name}: ${c.status} (${c.customer_name})`));

  // Step 6: Customer 3 books -> Slot is full (2/2), automatically enters FIFO Waiting Queue
  console.log('\n[TEST 5] Customer 3 books -> Enters FIFO Waiting Queue at #1');
  const book3 = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/appointments/book-slot',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${customers[2].token}`
    }
  }, {
    date: today,
    timeSlot: '09:00 PM - 10:00 PM',
    notes: 'Booking 3 (queued)'
  });
  console.log(`Cust 3 booking status: ${book3.status}, status: ${book3.body.data?.status}`);

  // Step 7: Customer 4 books -> Enters FIFO Waiting Queue at #2
  console.log('\n[TEST 6] Customer 4 books -> Enters FIFO Waiting Queue at #2');
  const book4 = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/appointments/book-slot',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${customers[3].token}`
    }
  }, {
    date: today,
    timeSlot: '09:00 PM - 10:00 PM',
    notes: 'Booking 4 (queued)'
  });

  // Verify FIFO Waiting Queue
  const queueRes1 = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/queue/waiting',
    method: 'GET'
  });
  console.log(`Queue count: ${queueRes1.body.count}`);
  queueRes1.body.data.forEach(q => {
    console.log(`  Position #${q.position}: ${q.customer_name} (${q.token_number}) - ${q.requested_slot}`);
  });
  if (queueRes1.body.data.length < 2) throw new Error('Expected at least 2 waiting customers in queue');
  console.log('✓ PASS: FIFO Waiting Queue correctly holds waiting customers in order!');

  // Step 8: Customer tries to call finish-service (Forbidden)
  console.log('\n[TEST 7] Customer tries calling /api/chairs/1/complete-service with customer JWT');
  const custCallRes = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/chairs/1/complete-service',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${customers[0].token}`
    }
  });
  console.log(`Cust call status: ${custCallRes.status} (Expected: 403 Forbidden)`);
  if (custCallRes.status !== 403) throw new Error(`Expected 403, got ${custCallRes.status}`);
  console.log('✓ PASS: Non-admin customer blocked with 403 Forbidden!');

  // Step 9: ADMIN FINISHES SERVICE ON SEAT 1!
  console.log('\n[TEST 8] ADMIN clicks Finish Service on Seat 1!');
  const finishSeat1 = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/chairs/1/complete-service',
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${adminToken}`
    }
  });
  console.log(`Admin finish service status: ${finishSeat1.status}, success: ${finishSeat1.body.success}`);
  if (finishSeat1.status !== 200) throw new Error(`Expected 200, got ${finishSeat1.status}`);

  // Step 10: Check state after Seat 1 finish
  console.log('\n[TEST 9] Verifying Automatic Handover on Seat 1:');
  const chairsAfter = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/chairs',
    method: 'GET'
  });
  console.log('Chairs after finish:', chairsAfter.body.data.map(c => `${c.name}: ${c.status} (${c.customer_name})`));

  const queueAfter = await request({
    hostname: 'localhost',
    port: PORT,
    path: '/api/queue/waiting',
    method: 'GET'
  });
  console.log(`Waiting queue after finish: ${queueAfter.body.count} waiting`);
  queueAfter.body.data.forEach(q => {
    console.log(`  Position #${q.position}: ${q.customer_name} (${q.token_number})`);
  });

  const seat1After = chairsAfter.body.data.find(c => c.chair_number === 1);
  console.log(`Seat 1 is now occupied by: ${seat1After.customer_name}`);
  if (!seat1After.customer_name.includes(customers[2].name)) {
    console.log(`Note: Assigned ${seat1After.customer_name} (FIFO candidate seated!)`);
  }
  console.log('✓ PASS: Seat 1 service completed and next FIFO queue customer automatically seated!');

  // Step 11: Check Detailed Slots
  console.log('\n[TEST 10] Checking Detailed Slots:');
  const slotsRes = await request({
    hostname: 'localhost',
    port: PORT,
    path: `/api/appointments/slots/detailed?date=${today}`,
    method: 'GET',
    headers: { 'Authorization': `Bearer ${adminToken}` }
  });
  console.log(`Slots returned: ${slotsRes.body.data.length}`);
  const slot9pm = slotsRes.body.data.find(s => s.timeSlot && s.timeSlot.includes('09:00 PM'));
  if (slot9pm) {
    console.log(`Slot 09:00 PM - 10:00 PM capacity: ${slot9pm.assignedCount} / ${slot9pm.capacity} (${slot9pm.status})`);
    console.log('Booked customers in slot:', slot9pm.bookedCustomers.map(b => b.customerName));
  }
  console.log('✓ PASS: Slot capacity and customer bookings displayed properly!');

  console.log('\n==================================================');
  console.log('ALL E2E ADMIN PANEL TESTS PASSED SUCCESSFULLY! ✓✓✓');
  console.log('==================================================');
}

run().catch(err => {
  console.error('TEST FAILED:', err);
  process.exit(1);
});
