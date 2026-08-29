/**
 * mock-shop 的状态层。
 *
 * ⚠️ 这里没有后端、没有数据库 —— 登录态、购物车、订单全在 storage 里。
 *    被测站点的职责只有一个：让 88 个存量定位器都能命中，并对操作做出确定的反应。
 *
 * ⚠️ 账号是写死的。案例里用 ${test_user} / @cred{test_user_password} 这类占位符，
 *    真实值由执行环境注入，必须与这里对得上（见 atp-runner 的 env profile）。
 */
const Shop = (() => {
  const USERS = {
    'tanaka@example.jp': { password: 'Passw0rd!', nickname: '田中 直樹', locked: false },
    'locked@example.jp': { password: 'whatever',  nickname: 'ロック済み', locked: true  },
  };

  // 登录态放 sessionStorage，「记住我」放 localStorage ——
  // ATP-LOGIN-0007 靠这个区别：不勾选时关掉标签页就登出，勾选后重开仍在登录态。
  const S = {
    get session() { return JSON.parse(sessionStorage.getItem('atp_session') || localStorage.getItem('atp_session') || 'null'); },
    login(user, remember, method) {
      const data = { user, nickname: USERS[user].nickname, method: method || 'PASSWORD' };
      (remember ? localStorage : sessionStorage).setItem('atp_session', JSON.stringify(data));
      sessionStorage.removeItem('atp_logged_out');
    },
    logout() {
      sessionStorage.removeItem('atp_session');
      localStorage.removeItem('atp_session');
      // ⭐ 标记「是主动登出，不是会话超时」。
      //    ATP-LOGIN-0006 登出后访问 /mypage 期望看到登录表单，
      //    而 ATP-LOGIN-0009 会话超时访问同一页却期望看到超时对话框 —— 靠这个标记区分。
      sessionStorage.setItem('atp_logged_out', '1');
    },
    get loggedOut() { return sessionStorage.getItem('atp_logged_out') === '1'; },
  };

  function authenticate(username, password) {
    const u = USERS[username];
    if (u && u.locked) return { ok: false, error: 'アカウントがロックされています', locked: true };
    if (!u || u.password !== password) {
      // 不区分「用户不存在」和「密码错误」—— ATP-LOGIN-0003 考察的就是不泄露账号是否存在
      return { ok: false, error: 'ユーザー名またはパスワードが正しくありません' };
    }
    return { ok: true, user: u };
  }

  /** 顶部导航。登录后的页面都要有 header[home-header] 与 nav[main-nav] */
  function renderHeader(container) {
    const s = S.session;
    container.innerHTML = `
      <header data-testid="home-header" class="site-header">
        <div class="brand">ATP Shop</div>
        <nav data-testid="main-nav" class="main-nav">
          <a href="/home">ホーム</a>
          <a href="/cart" data-testid="nav-cart">カート <span data-testid="cart-badge-count">${Cart.count()}</span></a>
          <a href="/orders">注文履歴</a>
        </nav>
        <div class="user-area">
          <span data-testid="login-method">${s ? s.method : ''}</span>
          <span data-testid="user-nickname">${s ? s.nickname : ''}</span>
          <button data-testid="user-menu-toggle" class="btn-plain">▾</button>
          <div id="user-menu" class="user-menu" hidden>
            <button data-testid="logout-button" class="btn-plain">ログアウト</button>
          </div>
        </div>
      </header>`;
    container.querySelector('[data-testid="user-menu-toggle"]').onclick = () => {
      const m = container.querySelector('#user-menu');
      m.hidden = !m.hidden;
    };
    container.querySelector('[data-testid="logout-button"]').onclick = () => {
      S.logout();
      location.href = '/login';
    };
  }

  /** 未登录时的守卫。返回 true 表示已拦截，调用方应停止渲染正常内容 */
  function guard(container) {
    if (S.session) return false;
    container.innerHTML = S.loggedOut ? loginFormHtml() : sessionExpiredHtml();
    if (S.loggedOut) bindLoginForm(container);
    return true;
  }

  function sessionExpiredHtml() {
    return `
      <div data-testid="session-expired-dialog" class="dialog">
        <h2>セッションの有効期限が切れました</h2>
        <p>再度ログインしてください。</p>
        <!-- ⚠️ 必须是 input 元素：案例的定位器是 //input[@data-testid="redirect-hint"]，
             而 ASSERT_TEXT 作用在 input 上时，执行器读的是 value 而不是 textContent -->
        <input data-testid="redirect-hint" value="${location.pathname}" readonly>
        <button data-testid="relogin-button" class="btn" onclick="location.href='/login?redirect=' + encodeURIComponent('${location.pathname}')">再ログイン</button>
      </div>`;
  }

  function loginFormHtml() {
    return `
      <form data-testid="login-form" class="login-form" onsubmit="return false">
        <h2>ログイン</h2>
        <label>ユーザー名
          <input data-testid="login-username" type="text" autocomplete="username">
          <span data-testid="username-field-error" class="field-error"></span>
        </label>
        <label>パスワード
          <input data-testid="login-password" type="password" autocomplete="current-password">
        </label>
        <label class="inline"><input data-testid="remember-me" type="checkbox"> ログイン状態を保持する</label>
        <div data-testid="login-error" class="form-error"></div>
        <button data-testid="login-submit" class="btn btn-primary">ログイン</button>
        <a data-testid="unlock-guide-link" href="/unlock-guide" hidden>アカウントロックの解除方法</a>
      </form>`;
  }

  function bindLoginForm(root) {
    const $ = (t) => root.querySelector(`[data-testid="${t}"]`);
    $('login-submit').onclick = () => {
      const username = $('login-username').value.trim();
      const password = $('login-password').value;
      $('username-field-error').textContent = '';
      $('login-error').textContent = '';
      $('unlock-guide-link').hidden = true;

      // 必填校验先于认证 —— ATP-LOGIN-0005 期望停在这里，不产生任何认证请求
      if (!username) {
        $('username-field-error').textContent = '必須項目です';
        return;
      }
      const r = authenticate(username, password);
      if (!r.ok) {
        $('login-error').textContent = r.error;
        if (r.locked) $('unlock-guide-link').hidden = false;
        return;
      }
      S.login(username, $('remember-me').checked);
      const redirect = new URLSearchParams(location.search).get('redirect');
      location.href = redirect || '/home';
    };
  }

  return { USERS, S, authenticate, renderHeader, guard, loginFormHtml, bindLoginForm };
})();
