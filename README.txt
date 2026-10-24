SPAWNERGUARD - Fabric 1.21.11 (client mod)

CACH BUILD (khong can cai gi tren may, dung GitHub):
1. Tao tai khoan GitHub, tao repo moi (Private cung duoc).
2. Giai nen file zip nay, upload TOAN BO file/thu muc (ke ca thu muc .github) len repo.
3. Vao tab Actions -> workflow "build" tu chay (2-5 phut).
4. Mo lan chay xong -> muc Artifacts -> tai "Artifacts" -> giai nen -> lay file spawnerguard-1.4.6-mc1.21.11.jar
   (KHONG lay file -sources.jar).
5. Bo spawnerguard-1.4.6-mc1.21.11.jar va Fabric API 0.141.6+1.21.11 vao thu muc mods cua PojavLauncher (profile Fabric 1.21.11).

CACH DUNG:
- Dung GIUA khung obsidian, go chat:  /sg center   (luu tam can cu, chi can lam 1 lan)
- /sg status  : xem tam, nguoi gan nhat, kiem tra cup Silk Touch + ender chest
- Mod luon bat. Chi hoat dong trong 40 block quanh tam can cu.
- Cup Silk Touch phai nam trong hotbar (do ben >= 20).
- Ender chest dat san trong 10 block.

TU DONG BAN (them o ban 1.1):
- /sg autosell on   : bat, moi 1 gio treo (khong co nguoi lai gan) mod tu chuot phai spawner, bam Sell All (phoi vang), bam kinh xanh xac nhan
- /sg autosell off  : tat (mac dinh la TAT)
- /sg sellnow       : ban thu ngay lap tuc de test

GUI CAI DAT (ban 1.2.0):
- Go /sg trong chat de mo menu cai dat. Moi chuc nang co nut BAT/TAT, tu luu lai.
- Bao ve spawner, Tu dong ban, Tu vao lai, Tu go /home 1, Tieng bao dong, Cui khi dao, Kiem tra truoc, Chay nen
- Nut doi: Ban kinh quet (50/75/100), Chu ky ban (15/30/60/90/120 phut)
- Nut: Luu tam can cu, Ban thu ngay

KICH HOAT (ban 1.4.0):
- Moi ma chi gan duoc voi 1 tai khoan Minecraft (may chu Google Apps Script ghi nho).
- Vao game go:  /sg key <ma>  - chi can 1 lan, luu vinh vien tren may, khong can nhap lai.
- Dia chi may chu dat trong file src/main/resources/spawnerguard-server.txt (thay PASTE_APPS_SCRIPT_URL_HERE).
- File may chu va ma goc (may-chu-kich-hoat.gs, ma-kich-hoat.txt) KHONG nam trong zip - giu bi mat.

