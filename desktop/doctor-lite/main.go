// claude-doctor-lite —— 超轻量 Claude 环境风险一条龙检测器
// 构建: go build -trimpath -ldflags "-s -w" -o ClaudeDoctorLite.exe
package main

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

// ---------- Windows 控制台 ANSI 颜色 + 系统 Locale ----------
var (
	kernel32        = syscall.NewLazyDLL("kernel32.dll")
	procSetMode     = kernel32.NewProc("SetConsoleMode")
	procGetStdHandle = kernel32.NewProc("GetStdHandle")
	procGetLocale   = kernel32.NewProc("GetUserDefaultLocaleName")
)

func enableANSI() {
	const stdOutputHandle = ^uintptr(11) // STD_OUTPUT_HANDLE = -11
	h, _, _ := procGetStdHandle.Call(stdOutputHandle)
	const enableVirtualTerminal = 0x0004
	procSetMode.Call(h, 0x0007|enableVirtualTerminal) // ENABLE_PROCESSED|WRAP_AT_EOL|VT
}

func systemLocale() string {
	buf := make([]uint16, 85)
	r, _, _ := procGetLocale.Call(uintptr(unsafe.Pointer(&buf[0])), 85)
	if r == 0 {
		return "unknown"
	}
	return syscall.UTF16ToString(buf)
}

// ---------- 颜色 ----------
var (
	cR = "\033[31m"
	cG = "\033[32m"
	cY = "\033[33m"
	cB = "\033[36m"
	cD = "\033[90m"
	c0 = "\033[0m"
)

type result struct {
	level int // 0 ok, 1 warn, 2 fail
	name  string
	detail string
	risk  int    // 加的风险分
	advice string
}

var results []result
var totalRisk int
var advices []string

func pass(name, detail string) { results = append(results, result{0, name, detail, 0, ""}); printResult(results[len(results)-1]) }
func warn(name, detail string, risk int, advice string) {
	results = append(results, result{1, name, detail, risk, advice})
	printResult(results[len(results)-1])
	totalRisk += risk
	if advice != "" {
		advices = append(advices, advice)
	}
}
func fail(name, detail string, risk int, advice string) {
	results = append(results, result{2, name, detail, risk, advice})
	printResult(results[len(results)-1])
	totalRisk += risk
	if advice != "" {
		advices = append(advices, advice)
	}
}

func printResult(r result) {
	switch r.level {
	case 0:
		fmt.Printf("  [%sOK%s]   %-22s %s\n", cG, c0, r.name, r.detail)
	case 1:
		fmt.Printf("  [%s!!%s]   %-22s %s\n", cY, c0, r.name, r.detail)
	case 2:
		fmt.Printf("  [%sXX%s]   %-22s %s\n", cR, c0, r.name, r.detail)
	}
}

// ---------- HTTP ----------
var client = &http.Client{Timeout: 10 * time.Second}

type whoisResp struct {
	IP         string `json:"ip"`
	Country    string `json:"country"`
	CC         string `json:"country_code"`
	City       string `json:"city"`
	ISP        string `json:"-"`
	Connection struct {
		ISP string      `json:"isp"`
		Org string      `json:"org"`
		ASN interface{} `json:"asn"` // ipwho.is 返回数字,ipapi.co 返回字符串
	} `json:"connection"`
}

// 统一取 ISP:兼容两个数据源
func (w *whoisResp) getISP() string {
	if w.Connection.ISP != "" {
		return w.Connection.ISP
	}
	return w.Connection.Org
}

func getJSON(url string, out interface{}) error {
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	return json.NewDecoder(resp.Body).Decode(out)
}

// 机房/云厂商关键词 → IP 类型判定
var dcKW = []string{"amazon", "aws", "google", "microsoft", "azure", "digitalocean", "linode",
	"vultr", "ovh", "hetzner", "contabo", "choopa", "alibaba", "aliyun", "tencent", "huawei",
	"oracle", "datacamp", "leaseweb", "hosting", "server", "cloud", "vps", "datacenter", "idc"}
var cnISP = []string{"china telecom", "china unicom", "china mobile", "chinatelecom", "cmcc",
	"alibaba", "tencent", "aliyun", "huawei", "bingdian", "wireguard", "vallen", "megalayer"}

func classify(isp string) (isCN, isDC bool) {
	s := strings.ToLower(isp)
	for _, k := range cnISP {
		if strings.Contains(s, k) {
			isCN = true
		}
	}
	for _, k := range dcKW {
		if strings.Contains(s, k) {
			isDC = true
		}
	}
	return
}

// 官方不支持地区 (Anthropic Supported Regions Policy, 2026-11-12 生效)
// 名单无以下地区; 其余 185 地区在列
var UNSUP = map[string]bool{"CN": true, "HK": true, "MO": true, "RU": true, "BY": true, "IR": true, "KP": true, "CU": true, "SY": true}

func section(t string) {
	fmt.Printf("\n%s━━ %s ━━%s\n", cB, t, c0)
}

func main() {
	enableANSI()
	start := time.Now()

	fmt.Printf("%s╔════════════════════════════════════════════╗%s\n", cB, c0)
	fmt.Printf("%s║  Claude Doctor Lite · 环境风险一条龙检测   ║%s\n", cB, c0)
	fmt.Printf("%s╚════════════════════════════════════════════╝%s\n", cB, c0)
	fmt.Printf("  %s纯本地分析,检测数据全部留存本地,不上传%s\n", cD, c0)

	// ============ 1. 网络出口 ============
	section("1. 网络出口 (IP 画像)")

	var g4 whoisResp
	var errGeo error
	// 主源 ipwho.is,备用 ipapi.co
	type ipapiResp struct {
		IP          string `json:"ip"`
		CountryName string `json:"country_name"`
		CC          string `json:"country_code"`
		City        string `json:"city"`
		Org         string `json:"org"`
	}
	errGeo = getJSON("https://ipwho.is/", &g4)
	if (errGeo != nil || g4.IP == "") {
		var f ipapiResp
		if err2 := getJSON("https://ipapi.co/json/", &f); err2 == nil && f.IP != "" {
			errGeo = nil
			g4 = whoisResp{IP: f.IP, Country: f.CountryName, CC: f.CC, City: f.City}
			g4.Connection.Org = f.Org
		}
	}
	if g4.IP == "" {
		fail("IPv4 出口", "获取失败 ("+errShort(errGeo)+") —— 检查网络/代理", 10, "确认代理已开启且环境变量 HTTPS_PROXY 生效")
	} else {
		isCN, isDC := classify(g4.getISP())
		cc := strings.ToUpper(g4.CC)
		fmt.Printf("  %s出口 IP: %s  (%s %s · %s)%s\n", cD, g4.IP, g4.Country, g4.City, g4.getISP(), c0)

		switch {
		case UNSUP[cc]:
			fail("IPv4 归属", cc+" —— 官方不支持地区 (Supported Regions Policy)", 35, "IP 出口在不支持地区,官方第①道门直接不通过,必须换支持地区的稳定出口")
		default:
			pass("IPv4 归属", cc+" (官方支持地区名单内)")
		}

		if isCN {
			fail("IPv4 运营商", "中国运营商/厂商网络 —— 疑似直连泄露", 20, "出口走的是中国网络,代理未生效或分流规则漏了 claude.ai / anthropic.com")
		}
		if isDC {
			warn("IPv4 类型", "疑似机房/云厂商/共享节点 IP", 15, "机房 IP 被滥用概率高、有连坐风险,建议独享住宅 IP")
		} else {
			pass("IPv4 类型", "疑似住宅/商业宽带")
		}
	}

	// ============ 2. IPv6 双栈泄露 ============
	section("2. IPv6 双栈泄露")
	type ipify struct {
		IP string `json:"ip"`
	}
	var v6 ipify
	if err := getJSON("https://api64.ipify.org?format=json", &v6); err == nil && strings.Contains(v6.IP, ":") {
		var g6 whoisResp
		if err := getJSON("https://ipwho.is/"+v6.IP, &g6); err == nil && g6.IP != "" || (err == nil && g6.CC != "") {
			cc6 := strings.ToUpper(g6.CC)
			cc4 := strings.ToUpper(g4.CC)
			isCN6, _ := classify(g6.getISP())
			if isCN6 {
				fail("IPv6 泄露", "IPv6 直连中国运营商网络 ("+cc6+")", 20, "存在 IPv6 泄露: 支持 v6 的域名会绕过代理直连暴露真实身份。代理开 TUN 模式或禁用 IPv6")
			} else if cc6 != cc4 {
				fail("IPv6 泄露", "v6("+cc6+") 与 v4("+cc4+") 国家不一致 —— 代理只接管了 IPv4", 20, "同上,IPv6 绕过了代理,务必开 TUN/全局或关闭 v6")
			} else {
				pass("IPv6 一致性", "v6 出口与 v4 一致 ("+cc6+")")
			}
		}
	} else {
		pass("IPv6", "无 IPv6 出口或已隔离 (不易泄露)")
	}

	// ============ 3. DNS 泄露 ============
	section("3. DNS 出口检测")
	txts, err := net.LookupTXT("o-o.myaddr.l.google.com")
	if err != nil || len(txts) == 0 {
		warn("DNS 出口", "无法探测 ("+errShort(err)+")", 0, "")
	} else {
		dnsIP := strings.Join(txts, ",")
		var gd whoisResp
		ccDNS := "?"
		if err := getJSON("https://ipwho.is/"+firstIP(dnsIP), &gd); err == nil {
			ccDNS = strings.ToUpper(gd.CC)
		}
		switch {
		case ccDNS == "CN":
			fail("DNS 泄露", "DNS 解析出口在中国 ("+dnsIP+")", 15, "DNS 走了国内运营商/公共 DNS,会暴露真实网络环境;在代理中锁死全局 DNS 或使用加密 DNS")
		case ccDNS == strings.ToUpper(g4.CC):
			pass("DNS 出口", "与 IP 出口一致 ("+ccDNS+" · "+dnsIP+")")
		default:
			warn("DNS 出口", "与 IP 出口不一致 (DNS:"+ccDNS+" / IP:"+strings.ToUpper(g4.CC)+")", 8, "DNS 与出口异地,存在分流泄露特征,建议全局接管 DNS")
		}
	}

	// ============ 4. 系统时区 ============
	section("4. 系统时区一致性")
	_, offSec := time.Now().Zone()
	offHours := offSec / 3600
	tzName := time.Local.String()
	tzCN := strings.Contains(tzName, "China") || strings.Contains(tzName, "Shanghai") ||
		strings.Contains(tzName, "Hong") || strings.Contains(tzName, "Macau") ||
		strings.Contains(tzName, "Taipei") || strings.Contains(tzName, "Urumqi") || offHours == 8
	ipCC := strings.ToUpper(g4.CC)
	fmt.Printf("  %s本地时区: %s UTC+%.0f  本地时间: %s%s\n", cD, tzName, float64(offHours), time.Now().Format("2006-01-02 15:04"), c0)
	if tzCN {
		switch ipCC {
		case "CN", "HK", "MO":
			warn("时区", "东八区 + 受限区 IP("+ipCC+") —— 双重暴露", 8, "时区与 IP 都指向受限区,叠加拉分")
		case "":
			warn("时区", "东八区 (出口 IP 未能定位,无法比对)", 5, "先解决网络出口检测失败的问题")
		default:
			warn("时区", "东八区 vs IP "+ipCC+" —— 时空冲突", 8, "IP 在海外但系统时区是东八区,典型穿越信号;把系统时区改成 IP 所在时区")
		}
	} else {
		pass("时区", "非 CN 系时区 ("+tzName+")")
	}

	// ============ 5. 系统语言/Locale ============
	section("5. 系统语言指纹")
	loc := systemLocale()
	langCN := strings.HasPrefix(strings.ToLower(loc), "zh")
	if langCN {
		warn("系统 Locale", loc+" —— 中文环境", 5, "首选语言是中文(低权重),建议浏览器 Profile 中把 en-US 设为首选语言")
	} else {
		pass("系统 Locale", loc)
	}

	// ============ 5b. 厂商字体指纹 (Windows) ============
	section("5b. 厂商字体指纹 (Windows)")
	vendorKW := []string{"MiSans", "HarmonyOS", "OPPO", "vivo", "Honor", "PuHuiTi", "FZLanTing", "HYZhuZiGuang", "Alibaba"}
	var foundFonts []string
	if entries, err := os.ReadDir(`C:\Windows\Fonts`); err == nil {
		for _, e := range entries {
			n := e.Name()
			for _, kw := range vendorKW {
				if strings.Contains(strings.ToLower(n), strings.ToLower(kw)) {
					foundFonts = append(foundFonts, n)
					break
				}
			}
		}
	}
	if len(foundFonts) >= 2 {
		warn("厂商字体", fmt.Sprintf("检测到 %d 款国产厂商预装字体 (%s)", len(foundFonts), strings.Join(foundFonts[:minInt(len(foundFonts), 4)], ", ")), 10, "系统装了小米/鸿蒙/OV/荣耀等厂商字体——设备是国产行货的硬指纹;不用的可卸载或改用干净 Profile")
	} else if len(foundFonts) == 1 {
		warn("厂商字体", "1 款厂商字体: "+foundFonts[0], 8, "存在厂商预装字体,轻微指纹信号")
	} else {
		pass("厂商字体", "未检出国产厂商字体")
	}

	// ============ 6. 代理配置 ============
	section("6. 代理配置")
	proxy := os.Getenv("HTTPS_PROXY")
	if proxy == "" {
		proxy = os.Getenv("https_proxy")
	}
	if proxy == "" {
		proxy = os.Getenv("HTTP_PROXY")
	}
	if proxy != "" {
		pass("系统代理变量", proxy)
	} else {
		warn("系统代理变量", "未设置 HTTPS_PROXY/HTTP_PROXY", 5, "未检测到代理环境变量;若直连受限地区网络,Anthropic 服务将不可达或泄露")
	}

	// ============ 7. Claude 服务可达性 ============
	section("7. Claude 服务可达性 / WAF")
	// claude.ai WAF 探测
	resp, err := client.Get("https://claude.ai")
	if err != nil {
		fail("claude.ai", "不可达 ("+errShort(err)+")", 15, "claude.ai 连不上,代理分流漏掉了该域名")
	} else {
		resp.Body.Close()
		switch {
		case resp.StatusCode == 403 || resp.StatusCode == 429:
			warn("claude.ai WAF", "HTTP "+fmt.Sprint(resp.StatusCode)+" —— Cloudflare 对当前 IP 发起人机验证/拦截", 15, "当前 IP 已被 Cloudflare 标记,网页端大概率不可用,建议换出口")
		case resp.StatusCode < 400:
			pass("claude.ai", "HTTP "+fmt.Sprint(resp.StatusCode)+" (正常)")
		default:
			warn("claude.ai", "HTTP "+fmt.Sprint(resp.StatusCode), 5, "")
		}
	}
	resp2, err := client.Get("https://api.anthropic.com/v1/models")
	if err != nil {
		fail("api.anthropic.com", "不可达 ("+errShort(err)+")", 10, "API 连不通,检查代理")
	} else {
		resp2.Body.Close()
		if resp2.StatusCode == 401 || resp2.StatusCode == 403 || resp2.StatusCode == 400 {
			pass("api.anthropic.com", "HTTP "+fmt.Sprint(resp2.StatusCode)+" (网络通,未带鉴权属正常)")
		} else {
			warn("api.anthropic.com", "HTTP "+fmt.Sprint(resp2.StatusCode), 5, "")
		}
	}

	// ============ 汇总 ============
	section("检测完成")
	totalRisk = minInt(totalRisk, 100)
	verdict, col := "", cG
	if totalRisk >= 60 {
		verdict, col = "🔴 高风险:多项关键信号冲突,不建议现在登录 Claude", cR
	} else if totalRisk >= 30 {
		verdict, col = "🟡 中风险:存在泄露/不匹配,建议先修复再登录", cY
	} else {
		verdict = "🟢 低风险:桌面端可测项基本一致"
	}
	fmt.Printf("\n  风险评分: %s%d / 100%s   %s\n", col, totalRisk, c0, verdict)
	fmt.Printf("  耗时 %.1fs\n\n", time.Since(start).Seconds())

	if len(advices) > 0 {
		fmt.Printf("%s需要处理的问题 (按权重排序):%s\n", cY, c0)
		for _, r := range results {
			if r.level == 0 || r.advice == "" {
				continue
			}
			icon := cY + "⚠" + c0
			if r.level == 2 {
				icon = cR + "✖" + c0
			}
			fmt.Printf("  %s %-14s %s\n      %s→ %s%s\n", icon, r.name, cD+r.detail+c0, cD, r.advice, c0)
		}
	}

	// 网页测不到的维度说明
	fmt.Printf("\n%s📱 SIM 卡说明: 网页/桌面端无法读取 SIM 归属国;仅安卓版 Claude App 可读(低权重信号)。%s\n", cD, c0)
	fmt.Printf("%s   Claude 真正查手机的环节是注册短信验证号的归属国(VoIP/接码秒封)。%s\n", cD, c0)
	fmt.Printf("%s   检测结果仅供参考,不代表 Anthropic 官方判断。%s\n\n", cD, c0)

	if totalRisk >= 60 {
		os.Exit(2)
	}
}

func firstIP(s string) string {
	s = strings.TrimSpace(strings.Trim(s, "\""))
	if i := strings.IndexAny(s, ",; "); i > 0 {
		s = s[:i]
	}
	return s
}

func errShort(err error) string {
	if err == nil {
		return "unknown"
	}
	s := err.Error()
	if len(s) > 60 {
		s = s[:60]
	}
	return s
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
