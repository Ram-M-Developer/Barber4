# BarberEase – Smart Appointment & Digital Queue Management System

A production-quality, real-time full-stack web application designed for barber shops and hair salons to manage physical chairs, appointment bookings, and virtual waiting queues. Built following the MVC architecture, it is suitable for an **M.Sc. Computer Science Mini Project**.

---

## 🚀 Key Features

* **Real-Time Chair Grid Map**: Displays the status of 8 chairs using live color indicators:
  * 🟢 **Green (Available)**: Clickable, ready for instant customer reservation.
  * 🟡 **Yellow (Reserved)**: Temporarily held with a 5-minute checkout countdown timer.
  * ⚫ **Grey (Occupied)**: Active session in progress.
  * 🔴 **Red (Maintenance)**: Out of service (disabled).
* **Temporary Reservation Holds**: Secures a chair dynamically during checkout. Includes a background cleanup thread to automatically release expired holds back to the available pool.
* **Smart Digital Queue tokens**: When all chairs are occupied, customers can join a virtual line to receive sequential daily token numbers (e.g., `T001`, `T002`) and track their queue position live.
* **Automatic Queue Seating**: When a barber completes an appointment, the system automatically seats the next customer from the queue into the vacated chair.
* **Admin Control Panel**: Full management tools for chairs, services CRUD, customer accounts, and real-time queue calling.
* **Analytical Business Reports**: Generates Daily, Monthly, Service Popularity, Peak Hours Traffic, and Chair Utilization reports.
* **Secure Web APIs**: Role-based access control, input schema validation, rate-limiting, and SQL Injection protection.

---

## 🛠️ Technology Stack

* **Frontend**: HTML5, CSS3, Bootstrap 5, Vanilla JavaScript, Socket.IO Client
* **Backend**: Node.js, Express.js, Socket.IO, Sequelize ORM, JWT, Bcrypt
* **Database**: MySQL

---

## 📂 Project Structure

```text
BarberEase/
│
├── config/
│   ├── app.js               # Environment config loader
│   └── database.js          # Sequelize connection manager
│
├── controllers/
│   ├── auth.controller.js
│   ├── customer.controller.js
│   ├── service.controller.js
│   ├── chair.controller.js
│   ├── appointment.controller.js
│   └── queue.controller.js
│
├── services/
│   ├── auth.service.js
│   ├── customer.service.js
│   ├── service.service.js
│   ├── chair.service.js
│   ├── appointment.service.js
│   ├── queue.service.js
│   └── report.service.js
│
├── routes/
│   ├── auth.routes.js
│   ├── customer.routes.js
│   ├── service.routes.js
│   ├── chair.routes.js
│   ├── appointment.routes.js
│   ├── queue.routes.js
│   ├── token.routes.js
│   └── report.routes.js
│
├── middleware/
│   ├── auth.js              # JWT verifier
│   ├── roleGuard.js         # Role check (customer vs. admin)
│   ├── errorHandler.js      # Sequelize & global error mapper
│   └── asyncWrapper.js      # Wraps controllers for clean code
│
├── models/
│   ├── index.js             # Associations (relations loader)
│   ├── Admin.js
│   ├── Customer.js
│   ├── Service.js
│   ├── Chair.js
│   ├── Appointment.js
│   ├── Queue.js
│   └── Token.js
│
├── validators/
│   ├── auth.validator.js
│   ├── booking.validator.js
│   └── validate.js          # Validator error catcher
│
├── utils/
│   └── helpers.js           # Token generators, wait calculators
│
├── database/
│   ├── schema.sql           # Reference database schema
│   ├── init.js              # Tables sync script
│   └── seed.js              # Admin & mock data seeder
│
├── public/
│   ├── auth/                # login.html, register.html, admin-login.html
│   ├── customer/            # dashboard.html, profile.html
│   ├── admin/               # dashboard.html
│   ├── css/                 # style.css
│   └── js/                  # auth.js, dashboard.js, profile.js, admin.js
│
├── views/
│   └── index.html           # Public landing page
│
├── .env.example             # Configuration template
├── .gitignore
├── package.json
└── server.js                # App entry point
```

---

## ⚙️ Installation & Setup

Follow these steps to run the application locally on your machine:

### 1. Prerequisite Checklist
* **Node.js**: Install from [nodejs.org](https://nodejs.org) (v16+ recommended).
* **MySQL**: Make sure your local MySQL server is active.

### 2. Database Creation
Open your MySQL terminal or database manager (e.g., phpMyAdmin, DBeaver) and run:
```sql
CREATE DATABASE barberease_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

### 3. Clone & Dependencies Installation
Navigate to your project root directory and run:
```bash
npm install
```

### 4. Configuration Setup
1. Copy `.env.example` to a new file named `.env`:
   ```bash
   cp .env.example .env
   ```
2. Open `.env` and fill in your local MySQL credentials:
   ```env
   DB_USER=your_mysql_username
   DB_PASSWORD=your_mysql_password
   JWT_SECRET=any_random_secure_string
   SESSION_SECRET=any_random_string
   ```

### 5. Table Initialization
Sync the Sequelize models to automatically create the database structure:
```bash
npm run db:init
```

### 6. Seeding Admin & Services Data
Insert default chairs, services, and the system administrator account:
```bash
npm run db:seed
```

### 7. Run Server
Start the development server:
```bash
npm run dev
```
For production environment:
```bash
npm start
```
Your server will start on [http://localhost:6080](http://localhost:6080).

---

## 👤 Default Accounts

* **Admin Portal Login**:
  * **Username**: `admin`
  * **Password**: `admin123`
* **Customer Login**:
  * Register a new user directly on the portal sign-up form.

---

## 🧪 Testing Checklist

Verify your installation works correctly by walking through these test cases:

### Case 1: Database Verification
- Run `npm run db:seed` and verify MySQL tables `admins`, `customers`, `services`, `chairs`, `appointments`, `queues`, and `tokens` are populated.

### Case 2: Customer Signup & Direct Booking
1. Open `http://localhost:6080/auth/register.html` and register a new account.
2. After redirection, click on an available chair (🟢) on the live map.
3. Confirm the reservation countdown timer (5:00 minutes) starts.
4. Select a service, date, time slot, and click **Confirm Appointment**.
5. Verify a confirmation token number is issued and details appear in the right-side summary.

### Case 3: Joining Queue
1. Log in as admin at `http://localhost:6080/auth/admin-login.html`. Select the **Chairs** tab and change all available chairs to **Maintenance** status.
2. Refresh the customer dashboard. Verify it displays "All chairs are occupied" and shows a **Join Digital Queue** button.
3. Click the button, select a service, and confirm.
4. Verify you receive a queue token (e.g., `T001`) with position `1`.

### Case 4: Real-Time Sockets Verification
1. Open two browser windows side-by-side:
   - Window A: Customer Dashboard (`http://localhost:6080/customer/dashboard.html`)
   - Window B: Admin Control Center (`http://localhost:6080/admin/dashboard.html`)
2. In Window A, click an available chair to reserve it.
3. Verify that the chair immediately turns Yellow (Reserved) in the Admin Control Grid (Window B) without a page refresh.
4. In Window B (Admin), click **Complete** on an active appointment.
5. Verify the chair immediately updates to Green (Available) in Window A.
