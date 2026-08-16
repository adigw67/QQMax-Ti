# QQMax-Ti 最终用户许可协议（EULA）/ End-User License Agreement

- **生效日期 / Effective date:** 2026-08-16
- **版本 / Version:** 1.3
- **许可方 / Licensor:** QQMax-Ti 项目作者（本仓库维护者，以下简称"作者"）

> 本协议以中文与英文两种语言提供。如两版本有冲突，**以中文版本为准**。
> This Agreement is provided in Chinese and English. **In case of any conflict, the Chinese version prevails.**

---

## 中文版

### 1. 定义与接受

1.1 "**本软件**"指 QQMax-Ti 及其官方发行包，具体包括：
   - (a) **安装包**：由作者构建并发布的 Android 安装包（APK）及其更新版本；
   - (b) **源代码**：本仓库中作者自研的代码、资源与配置（含 `app/src/main/java/momoi/` 模块、ApkMixin 构建工具等）；
   - (c) **文档**：随软件提供的说明文档（含本 EULA）。

1.2 "**第三方组件**"指不属于作者开发，但在本软件运行、构建或分发过程中涉及的外部代码、资源或服务，包括但不限于腾讯 QQ 原版组件、Xposed 框架、核心破解工具、开源字体（MiSans / Unifont）及其他第三方库。

1.3 "**您**"指下载、安装、运行或以其他方式使用本软件的个人或实体。

1.4 下载、安装、运行或使用本软件，即表示您已阅读、理解并同意受本协议约束。**若您不同意本协议，请勿使用本软件并删除其全部副本。**

1.5 若您为未成年人，您应在监护人知情并同意的前提下使用本软件，且监护人应对您的使用行为承担相应责任。

### 2. 授权范围

2.1 **源代码与资源（GPL）**：本仓库（含本软件自研部分的源代码与资源）依据随附的 [`LICENSE`](LICENSE)——**GNU General Public License，版本 3（GPL-3.0）**——授权。就源代码与资源而言，您被授予的权利、义务与限制以 **GPL-3.0** 为准，包括但不限于：使用、复制、修改、再分发及向使用者提供对应源代码的义务（Copyleft）。

2.2 **安装包（EULA）**：本 EULA 是针对**官方发行包（二进制 APK）**的补充条款，授权您在本人合法拥有的设备上，为**个人或非商业目的**安装与使用官方发行包，并就接受方式、可接受使用、免责、第三方组件、终止及争议解决等事项作出约定。本 EULA 不扩大、不替代 GPL-3.0 已明确授予的权利；就发行包中二进制特有的事项及与 GPL-3.0 不冲突的条款，以本 EULA 为准。

2.3 **商业使用**：任何商业用途（包括但不限于收费下载、捆绑销售、付费技术服务、广告或流量变现、将本软件用于经营行为）均须事先取得作者的**书面授权**。

2.4 **修改与再分发**：
   - (a) 您可以为个人用途 fork、修改本仓库；
   - (b) 公开发布修改版或衍生版，须事先取得作者的书面授权，并在发布物中**显著保留**作者版权声明与本 EULA 的链接；
   - (c) 不得使用"QQMax-Ti"等原项目名称或标识误导他人，使其误认为发布物为官方版本；
   - (d) 修改版引发的任何问题与作者无关。

2.5 **腾讯 QQ**：本软件是第三方修改程序，在腾讯手表版 QQ（包名 `com.tencent.qqlite`）原版字节码基础上注入自研功能，并以保留腾讯原签名的方式重新组装发行。**腾讯 QQ 的原版代码、资源、签名与商标不属于本项目，其全部权利归腾讯公司及其权利人所有；本协议不授予您任何腾讯 QQ 的权利。** 原版 QQ 须由您自行从合法渠道获取。

### 3. 免责声明

3.1 **非官方、无隶属**：本软件是独立的第三方互操作性项目，与腾讯公司（Tencent）及 QQ **无任何隶属、合作、授权或背书关系**。"QQ""腾讯"等名称及标识为其各自所有者的商标，本软件使用仅为描述兼容对象，不构成任何授权或认可。

3.2 **用途**：除非事先取得书面授权，本软件仅供**个人或其他非商业用途**使用，包括学习、研究、技术互操作与个人存档。

3.3 **合规义务**：您须自行确保对本软件的使用符合一切适用法律法规，以及您所接入平台的规定（包括但不限于《QQ 用户协议》与相关服务条款）。

3.4 **按"现状"提供**：本软件按"**现状（AS-IS）**"及"**现有（AS-AVAILABLE）**"提供，不附带任何明示或默示的担保，包括但不限于对适销性、特定用途适用性、不侵权、可用性、准确性、安全性或不中断的担保。

3.5 **责任限制**：在适用法律允许的最大范围内，对于因使用或无法使用本软件而导致的任何直接、间接、偶然、特殊、惩罚性或后果性损害（包括但不限于：账号封禁或限制、消息与数据丢失、利润或业务损失、设备系统异常、性能下降、异常重启、存储异常、其他应用不可用、人身或财产损害），作者**概不承担责任**，即使作者已被告知此类损害可能发生。

3.6 **风险自负**：您理解并自愿承担以下风险：
   - (a) 以修改字节码方式使用非官方版本接入腾讯平台，**可能违反腾讯服务条款并导致账号被限制或封禁**；
   - (b) 本软件依赖 root、Xposed 等系统级修改，可能导致设备不稳定、安全风险或保修失效；
   - (c) 覆盖安装、签名保留等机制可能与特定设备/系统版本不兼容，导致应用异常或无法启动；
   - (d) 官方 QQ 或系统升级可能导致本软件部分或全部功能失效。

3.7 **版本兼容性**：作者**不承诺**本软件与任何特定 QQ 版本、系统版本或设备的兼容性，也不承诺在官方更新后继续提供修复。

3.8 **隐私与数据**：本软件**不主动收集、上传或共享您的个人信息**。调试日志仅保存在您设备本地，其中可能包含消息内容摘要，请勿将日志泄露给不可信第三方；您对使用本软件产生的数据及其处理负全部责任。

3.9 **第三方平台与服务**：作者对以下事项**不承担任何责任**：第三方平台（含腾讯 QQ）对您账号采取的限制、封禁或其他措施；第三方接口（如 B 站等）失效、变更或拒绝服务；字体包等外部资源下载源的不可用或内容变更。

3.10 **设备与数据**：作者对因安装、卸载、升级或使用本软件导致的设备损坏、系统异常、数据（含聊天记录、账号数据、照片、文件）丢失或损坏**不承担任何责任**；升级或重装本软件可能重置部分设置，您应自行备份重要数据。

3.11 **非建议声明**：本软件**不构成任何技术建议、推荐或邀请**。是否安装与使用本软件，完全由您自主判断与决定；作者不因您采纳或拒绝本软件而承担任何责任。

3.12 **无承诺**：作者**不承诺**本软件包含任何特定功能，不承诺其性能、正确性或可用性，不承诺提供更新、修复或技术支持，也不承诺修复任何已知或未知缺陷。

3.13 **第三方内容与链接**：本软件中展示、跳转或解析的第三方内容、链接、图片、视频及信息（如 B 站视频信息等）均来自第三方，与作者无关；作者不对其真实性、合法性、可用性承担任何责任。

3.14 **地域合法性**：作者**不保证**本软件在您所在国家或地区的法律框架下合法。若当地法律禁止使用此类软件，您应立即停止使用；因使用产生的任何法律后果由您自行承担。

3.15 **风险规避建议**：若您无法接受本协议所述任何风险，请**不要安装或使用**本软件，并立即删除已获取的全部副本。

### 4. 可接受使用

您**不得**将本软件用于：

(a) 任何违法犯罪活动；
(b) 未经授权访问、干扰、破坏任何系统、网络或他人设备；
(c) 发送垃圾信息、实施骚扰、欺诈、钓鱼或传播恶意内容；
(d) 侵犯他人隐私、肖像、知识产权或其他合法权益；
(e) 任何违反所接入平台服务条款的行为；
(f) 未经书面授权的任何商业用途；
(g) 以任何形式向不具备判断能力的第三方（尤其未成年人）隐瞒风险后分发本软件；
(h) 利用本软件冒充官方产品，或作出官方认可的虚假陈述。

### 5. 第三方组件

5.1 **腾讯 QQ 原版组件**：发行包中包含腾讯 QQ 原版 APK 的组件（代码、资源、签名与商标均归腾讯公司所有）。作者不对其作出任何陈述或担保，亦不授予其任何权利；您不得将其与本软件分离后单独再分发、转售或再许可。

5.2 **运行环境组件**：root 权限、Xposed 框架、核心破解（CorePatch）等工具为第三方提供，作者不提供、不担保，其安装与使用风险由您自行承担。

5.3 **开源字体**：联网字体包中的 MiSans（版权归小米公司）与 GNU Unifont 等字体版权归其各自权利人；本软件仅按其各自许可在其允许范围内使用。

5.4 **GNU Unifont 与 GPL**：本软件使用的 **GNU Unifont** 依据 **GNU General Public License（GPL），版本 2 或（由您选择的）任何更高版本** 授权，并带有 GNU Unifont 字体嵌入例外条款。就该字体及其衍生作品而言，您被授予的权利、义务与限制以 **GNU GPL** 文本为准，包括但不限于：
   - (a) 您可以在遵守 GPL 的前提下使用、复制、修改与再分发该字体；
   - (b) 您对该字体或依据 GPL 修改的衍生作品进行再分发时，须随附 GPL 许可文本并保持版权声明；
   - (c) 在字体嵌入例外允许的范围内，将字体嵌入其他作品不受 GPL 传染性条款约束。

5.5 **开源许可优先**：本仓库源代码依据 **GPL-3.0** 授权（见第 2.1 条），所用 GNU Unifont 依据 **GPL-2.0 或更高版本**授权。本软件中受 GPL 或其他开源许可（"**开源许可**"）约束的任何部分，其许可范围、条件与限制均以相应开源许可为准。**当本协议与适用的开源许可发生冲突时，就开源许可所覆盖的部分，以开源许可为准**；本协议不限制、不取代开源许可已明确授予您的权利。发行包中二进制特有的、与开源许可不冲突的条款继续适用。

5.6 **逆向工程限制**：除适用法律强制性规定明确允许、权利人自身授权或适用的开源许可明确授予外，您不得对本软件或腾讯 QQ 进行规避技术保护措施的逆向工程、反编译、反汇编、破解或绕过操作。

### 6. 终止

若您违反本协议，相关授权将**自动立即终止**，无需作者另行通知。终止后，您应停止使用本软件并删除其全部副本与安装包。

### 7. 适用法律与争议解决

本协议的订立、效力、解释、履行及争议解决，均适用**中华人民共和国大陆地区法律**（不含其冲突法规则）。因本协议引起或与之相关的任何争议，双方应首先友好协商解决；协商不成的，任何一方可向**有管辖权的人民法院**提起诉讼。

### 8. 协议的变更

作者可不时更新本协议。更新后的版本将随发行包发布并注明生效日期与版本号。在更新生效后继续使用本软件，即视为您接受更新后的条款。

### 9. 一般条款

9.1 **可分性**：本协议任何条款被认定为无效或不可执行，不影响其余条款的效力。

9.2 **弃权**：作者未行使或迟延行使本协议项下任何权利，不构成对该权利的放弃。

9.3 **完整协议**：本协议构成您与作者之间关于本软件的完整约定，取代此前任何口头或书面的理解与沟通。

9.4 **不可转让**：未经作者书面同意，您不得转让本协议项下的任何权利或义务。

---

## English Version

### 1. Definitions and Acceptance

1.1 "**Software**" means QQMax-Ti and its official distribution packages, including (a) installation packages (APK) built and released by the author and their updates; (b) source code, self-developed resources, and configuration in this repository (including the `app/src/main/java/momoi/` modules and the ApkMixin build tool); and (c) accompanying documentation (including this EULA).

1.2 "**Third-Party Components**" means external code, resources, or services not developed by the author but involved in the Software's operation, build, or distribution, including but not limited to Tencent QQ's original components, the Xposed framework, core-patch tools, open-source fonts (MiSans / Unifont), and other third-party libraries.

1.3 "**You**" means the individual or entity that downloads, installs, runs, or otherwise uses the Software.

1.4 By downloading, installing, running, or using the Software, you acknowledge that you have read, understood, and agree to be bound by this Agreement. **If you do not agree, do not use the Software and delete all copies.**

1.5 If you are a minor, you shall use the Software only with the knowledge and consent of your guardian, who bears corresponding responsibility for your use.

### 2. Scope of License

2.1 **Source code and assets (GPL).** This repository (including the self-developed source code and assets of the Software) is licensed under the accompanying [`LICENSE`](LICENSE), the **GNU General Public License, version 3 (GPL-3.0)**. As to the source code and assets, your rights, obligations, and restrictions are governed by **GPL-3.0**, including, without limitation, the rights to use, copy, modify, and redistribute, and the copyleft obligation to provide corresponding source code to recipients.

2.2 **Installation packages (EULA).** This EULA provides **supplemental terms for official binary distribution packages (APK)**, licensing You to install and use them on devices You lawfully own for **personal or Non-Commercial purposes**, and addressing acceptance, acceptable use, disclaimers, third-party components, termination, and dispute resolution. It does not expand or replace rights expressly granted by GPL-3.0; matters specific to the binary distribution package and provisions that do not conflict with GPL-3.0 are governed by this EULA.

2.3 **Commercial Use.** Any Commercial Use (including paid downloads, bundling for sale, paid technical services, advertising or traffic monetization, or using the Software in business operations) requires the author's prior **written authorization**.

2.4 **Modification and redistribution.**
   - (a) You may fork and modify this repository for personal use;
   - (b) Public release of a modified or derivative version requires the author's prior written authorization and must **conspicuously retain** the author's copyright notice and a link to this EULA;
   - (c) You may not use the "QQMax-Ti" name or marks in a way that misleads others into believing the release is official;
   - (d) The author bears no responsibility for issues arising from modified versions.

2.5 **Tencent QQ.** The Software is a third-party modification that injects self-developed functionality into Tencent's watch QQ (package `com.tencent.qqlite`) and redistributes it while preserving Tencent's original signature. **Tencent QQ's original code, assets, signature, and trademarks are not part of this project; all such rights belong to Tencent and its rights holders, and this Agreement grants You no rights in Tencent QQ.** You must obtain the original QQ from lawful channels.

### 3. Disclaimers

3.1 **Not official; no affiliation.** The Software is an independent, third-party interoperability project and has **no affiliation, partnership, authorization, or endorsement relationship** with Tencent or QQ. "QQ", "Tencent", and related marks belong to their respective owners and are used only to describe the compatible target.

3.2 **Purpose.** Unless prior written authorization has been obtained, the Software may be used only for **personal or other Non-Commercial purposes**, including study, research, technical interoperability, and personal archival use.

3.3 **Compliance.** You are responsible for ensuring that your use complies with all applicable laws and with the terms of any platform you connect to (including the QQ User Agreement and related terms of service).

3.4 **AS-IS.** The Software is provided "**AS IS**" and "**AS AVAILABLE**", without warranties of any kind, express or implied, including merchantability, fitness for a particular purpose, non-infringement, availability, accuracy, security, or uninterrupted operation.

3.5 **Limitation of liability.** To the maximum extent permitted by law, the author shall **not be liable** for any direct, indirect, incidental, special, punitive, or consequential damages (including account suspension or restriction, message or data loss, lost profits or business, device malfunction, performance degradation, abnormal restarts, storage anomalies, unavailability of other applications, or personal or property damage) arising from use of or inability to use the Software.

3.6 **Assumption of risk.** You understand and voluntarily assume the following risks:
   - (a) using an unofficial, bytecode-modified version to access Tencent's platform **may violate Tencent's terms of service and lead to account restriction or suspension**;
   - (b) the Software depends on system-level modifications such as root and Xposed, which may cause device instability, security risks, or voided warranties;
   - (c) overwrite installation and signature-preserving mechanisms may be incompatible with specific devices or system versions;
   - (d) official QQ or system updates may disable part or all of the Software's functionality.

3.7 **Version compatibility.** The author makes **no promise** of compatibility with any specific QQ version, system version, or device, nor of continued fixes after official updates.

3.8 **Privacy and data.** The Software **does not proactively collect, upload, or share your personal information**. Debug logs are stored only locally on your device and may contain message-content excerpts; do not disclose logs to untrusted third parties. You are solely responsible for data generated by your use of the Software.

3.9 **Third-party platforms and services.** The author accepts **no liability** for: any restriction, suspension, or other measure taken by third-party platforms (including Tencent QQ) against your account; the failure, change, or refusal of third-party interfaces (such as Bilibili); or the unavailability or content changes of external resource download sources such as font packs.

3.10 **Devices and data.** The author accepts **no liability** for device damage, system malfunction, or loss or corruption of data (including chat history, account data, photos, and files) arising from installing, uninstalling, upgrading, or using the Software. Upgrading or reinstalling may reset some settings; you should back up important data yourself.

3.11 **Not advice.** The Software **does not constitute technical advice, recommendation, or solicitation**. Whether to install or use the Software is entirely your own decision; the author bears no liability for your decision to adopt or decline it.

3.12 **No promises.** The author **does not promise** that the Software contains any particular feature, nor its performance, correctness, or availability, nor the provision of updates, fixes, or technical support, nor the repair of any known or unknown defects.

3.13 **Third-party content and links.** Third-party content, links, images, videos, and information displayed, opened, or parsed by the Software (such as Bilibili video information) originate from third parties and are unrelated to the author; the author bears no liability for their truthfulness, legality, or availability.

3.14 **Territorial legality.** The author **does not guarantee** that the Software is lawful in your country or region. If local law prohibits such software, you must stop using it immediately; any legal consequences arising from use are entirely your own.

3.15 **Risk-avoidance advice.** If you cannot accept any risk described in this Agreement, **do not install or use** the Software, and immediately delete all copies you have obtained.

### 4. Acceptable Use

You shall **not** use the Software for: (a) any unlawful activity; (b) unauthorized access to, interference with, or disruption of any system, network, or others' devices; (c) spam, harassment, fraud, phishing, or malicious content; (d) infringement of others' privacy, likeness, intellectual-property, or other lawful rights; (e) any conduct that violates the terms of service of a connected platform; (f) any Commercial Use without prior written authorization; (g) distributing the Software to third parties incapable of assessing the risks (especially minors) without disclosing them; or (h) impersonating an official product or falsely claiming official endorsement.

### 5. Third-Party Components

5.1 **Tencent QQ original components.** Distribution packages contain components of Tencent's original QQ APK (code, assets, signature, and trademarks owned by Tencent). The author makes no representations or warranties regarding them and grants no rights in them; You may not separate them from the Software and redistribute, resell, or sublicense them independently.

5.2 **Runtime components.** Root access, the Xposed framework, core-patch tools, and similar utilities are provided by third parties. The author does not provide or warrant them; installation and use risks are entirely Your own.

5.3 **Open-source fonts.** Fonts in the online font pack, such as MiSans (copyright Xiaomi) and GNU Unifont, belong to their respective rights holders and are used only to the extent permitted by their respective licenses.

5.4 **GNU Unifont and GPL.** The **GNU Unifont** font used by the Software is licensed under the **GNU General Public License (GPL), version 2 or (at your option) any later version**, with the GNU Unifont font-embedding exception. For that font and its derivative works, your rights, obligations, and restrictions are governed by the text of the **GNU GPL**, including, without limitation:
   - (a) you may use, copy, modify, and redistribute the font in compliance with the GPL;
   - (b) when redistributing the font or GPL-modified derivative works, you must include the GPL license text and retain copyright notices;
   - (c) to the extent permitted by the font-embedding exception, embedding the font in other works is not subject to the GPL's copyleft provisions.

5.5 **Open-source license prevails.** This repository's source code is licensed under **GPL-3.0** (see Section 2.1), and the GNU Unifont font used by the Software is licensed under **GPL-2.0 or later**. Any part of the Software subject to the GPL or another open-source license (an "**Open-Source License**") is governed by that Open-Source License as to its scope, conditions, and restrictions. **Where this Agreement conflicts with an applicable Open-Source License, the Open-Source License prevails with respect to the covered part**; this Agreement does not restrict or replace rights expressly granted by an Open-Source License. Provisions specific to the binary distribution package that do not conflict with an Open-Source License continue to apply.

5.6 **Reverse-engineering restrictions.** Except to the extent expressly permitted by mandatory applicable law, authorized by the rights holder, or expressly granted by an applicable Open-Source License, You may not reverse engineer, decompile, disassemble, circumvent, crack, or bypass technical-protection measures in the Software or Tencent QQ.

### 6. Termination

If you breach this Agreement, the granted rights **terminate automatically and immediately**, without further notice. Upon termination, you must cease using the Software and delete all copies and installation packages.

### 7. Governing Law and Disputes

This Agreement is governed by the laws of the **mainland of the People's Republic of China** (excluding its conflict-of-laws rules). Any dispute arising out of or relating to this Agreement shall first be resolved through good-faith negotiation; failing that, either party may bring an action before a **court of competent jurisdiction**.

### 8. Changes

The author may update this Agreement from time to time. Updated versions will be published with the distribution package and bear an effective date and version number. Continued use after an update takes effect constitutes acceptance of the updated terms.

### 9. General Provisions

9.1 **Severability.** If any provision of this Agreement is held invalid or unenforceable, the remaining provisions remain in effect.

9.2 **Waiver.** The author's failure or delay in exercising any right under this Agreement does not constitute a waiver of that right.

9.3 **Entire agreement.** This Agreement constitutes the entire agreement between You and the author regarding the Software, superseding any prior oral or written understandings.

9.4 **Non-transferability.** You may not transfer any rights or obligations under this Agreement without the author's prior written consent.

---

**再次提醒：本软件为第三方修改程序，使用有风险，请自行评估后使用。因使用本软件产生的一切后果，均由使用者自行承担。**
