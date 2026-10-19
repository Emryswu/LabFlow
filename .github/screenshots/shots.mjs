// What the README screenshots show and how to tell each page has finished loading.
// frontend/tests/readme-screenshots.test.mjs checks every selector here still exists in
// the app, so a UI change that would break the screenshot run fails the normal test suite.

export const LOGIN = {
  ready: "[data-demo]",
  username: 'input[autocomplete="username"]',
  password: 'input[autocomplete="current-password"]',
  submit: ".login-submit",
  signedIn: "[data-nav]",
};

/** Demo accounts seeded by DemoUserInitializer, one browser session each. */
export const SESSIONS = [
  { user: null, shots: [{ file: "01-login.png", ready: "[data-demo]", fullPage: false }] },
  {
    user: "admin", password: "admin123",
    shots: [
      { file: "02-dashboard-admin.png", ready: ".row-item" },
      { file: "03-equipment.png", nav: "equipment", ready: ".equipment-table tbody tr" },
      { file: "05-workorders.png", nav: "workorders", ready: "[data-workorder-id]" },
      { file: "06-audit.png", nav: "audit", ready: "[data-audit-id]" },
    ],
  },
  { user: "teacher", password: "teacher123", shots: [{ file: "04-reservations.png", nav: "reservations", ready: ".week-block" }] },
  { user: "student", password: "student123", shots: [{ file: "07-dashboard-student.png", ready: ".row-item" }] },
  { user: "technician", password: "tech123", shots: [{ file: "08-workorders-technician.png", nav: "workorders", ready: "[data-workorder-id]" }] },
];
