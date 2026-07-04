# VisCheck — Analiza implementacji Java vs C++

## Wynik porównania Java ↔ C++

Algorytm ray-cast (Möller–Trumbore), BVH builder i AABB traversal są **identyczne** z C++. Nie ma błędów implementacyjnych w logice vischeck per se.

---

## Znaleziony problem: Filtr krawędzi MAX_EDGE_LENGTH = 800f

### Jak to działa
Java `OptimizedGeometry.loadFromBytes()` **filtruje trójkąty przy wczytywaniu**, usuwając te z krawędzią > 800 jednostek.  
C++ `OptimizedGeometry::LoadFromFile()` — **brak jakiegokolwiek filtrowania**, wczytuje wszystko.

Pliki `.opt` w projekcie zostały wygenerowane przez **C++ VPhysToOpt** (bez filtrowania), więc zawierają surową geometrię.

---

## Dlaczego de_dust2 działa, a de_mirage nie

### de_dust2.opt — analiza
| Mesh | Trójkąty |
|------|----------|
| mesh[0] | 12 147 |
| mesh[1] | 2 320 |
| mesh[2] | 14 194 |
| **mesh[3]** | **175 380** |
| mesh[4] | 42 |
| mesh[5] | 266 |

- mesh[3] to główna geometria mapy — trójkąty mają krawędzie **2300–2950 jednostek**
- Java **wyfiltrowuje** te wielkie trójkąty przy wczytywaniu (> 800 units)
- Zostają tylko solidne, małe ściany i podłogi → vischeck działa poprawnie

### de_mirage.opt — analiza
| Mesh | Trójkąty |
|------|----------|
| **mesh[0]** | **86 246** |
| mesh[1] | 2 |
| mesh[2] | 662 |
| mesh[3] | 4 |
| mesh[4] | 6 |
| mesh[5] | 76 |

- mesh[0] to CAŁA geometria mapy — jedna wielka "zupa" trójkątów
- Max krawędź: **796 jednostek** — poniżej progu 800f, więc filtr NIC nie usuwa
- **Bounds mesh[0]**: X[-4096, 1824], Y[-3864, 2048], Z[-448, 698]

**Problem**: De_mirage ma ~86k trójkątów w jednym meshu, przy czym wiele z nich to:
1. Trójkąty podłogi i sufitu (płaszczyzny poziome) — blokują ray-casty poziome i nie powinny blokować widoku poziomego gracza-do-gracza
2. Geometria displacement/terenu z krawędziami bliskimi limitu — nie zostają odfiltrowane

---

## Dlaczego ray-cast nie blokuje na mirage w niektórych miejscach

### Hipoteza 1: Brakuje geometrii ścian
Plik `de_mirage.opt` ma **tylko 86k trójkątów** w mesh[0] vs dust2 który ma 204k łącznie. Mirage jest geometrycznie bardziej skomplikowana (wiele warstw, budynki), więc część geometrii może po prostu **nie być uwzględniona w pliku vphys**. Pliki `.vphys` w CS2 zawierają tylko geometrię kolizji fizycznych — nie zawsze pokrywają się z tym co widzimy wzrokowo.

### Hipoteza 2: Wrong face culling — ray hit tylko z jednej strony
W Möller–Trumbore z back-face culling, ray trafiający od tyłu trójkąta zwraca `t <= epsilon` i jest ignorowany. C++ i Java mają **brak back-face culling** (sprawdzają `a > -epsilon && a < epsilon` symetrycznie), więc to **nie jest problem**.

### Hipoteza 3: Nieprawidłowy eye-height offset
W `ESPModule.java`:
```java
Vector3 localCamera = new Vector3(localOrigin.x, localOrigin.y, localOrigin.z + 64.0f);
Vector3 targetHead  = new Vector3(player.worldX, player.worldY, player.worldZ + 72.0f);
```
CS2 eye height = **64 jednostki** dla gracza stojącego (76 gdy pełna wysokość, ~64 od `m_vOldOrigin`). Target head offset 72 może być za wysoki lub za niski zależnie od pozycji gracza (kuczający = 46 units). To nie powoduje false-visibility, ale może powodować false-block gdy ray przechodzi przez własną geometrię.

### Hipoteza 4: Ray zaczyna się WEWNĄTRZ geometrii
Jeśli `localCamera` (origin + 64f) jest wewnątrz jakiegoś trójkąta (np. przy pochyleniu głowy do ściany, crouch), Möller–Trumbore zwróci `t > 0` dla tej pierwszej geometrii i wizcheck powie "blocked" mimo że gracz widzi cel.

### Hipoteza 5 (NAJBARDZIEJ PRAWDOPODOBNA): Plik .opt de_mirage nie zawiera pełnej geometrii
Duże mapy takie jak de_mirage, de_inferno mają swoje ściany w wielu plikach `.vphys`. Jeśli przy generowaniu `.opt` użyto tylko jednego `.vphys`, brakuje geometrii wielu ścian. Stąd ray-cast ich "nie widzi" i zawsze zwraca visible.

---

## Weryfikacja: Porównanie rozmiarów plików

| Mapa | Rozmiar .opt | Trójkąty |
|------|-------------|----------|
| de_dust2 | 7.4 MB | 204 349 |
| de_mirage | **3.1 MB** | 86 996 |
| de_nuke | 2.9 MB | 80 451 |
| de_inferno | 88.7 MB | 2 462 734 |
| de_overpass | 24.4 MB | 678 669 |
| de_cache | 54.8 MB | 1 521 387 |

**de_mirage ma zaledwie 3.1 MB i ~87k trójkątów** — to jest podejrzanie mało jak na tak rozbudowaną mapę. de_inferno ma 88 MB! To silnie sugeruje, że plik mirage.opt pochodzi tylko z części pliku `.vphys` mapy.

---

## Rekomendowane poprawki

### Fix 1: Regeneracja pliku de_mirage.opt
Należy sprawdzić czy przy generowaniu `de_mirage.opt` były uwzględnione wszystkie pliki `.vphys` dla tej mapy. W CS2 mapa może mieć kilka plików fizycznych (np. `de_mirage.vphys`, `de_mirage_bulk0.vphys` itd.).

### Fix 2: Weryfikacja eye-height w ESPModule
```java
// Aktualne (może być zbyt wysokie przy kucaniu):
Vector3 localCamera = new Vector3(localOrigin.x, localOrigin.y, localOrigin.z + 64.0f);

// CS2 realne eye heights:
// Standing: ~64 units nad m_vOldOrigin (origin jest na stopach)
// Crouching: ~46 units
// Warto czytać m_pCameraServices->m_vecCameraOffset lub użyć stałej 64f
```

### Fix 3: Ray origin epsilon bump
Aby uniknąć self-intersection (ray startujący wewnątrz geometrii), dodać małe przesunięcie w kierunku celu:
```java
float BUMP = 2.0f;  // 2 jednostki w kierunku celu
Vector3 bumpedOrigin = new Vector3(
    localCamera.x + normDir.x * BUMP,
    localCamera.y + normDir.y * BUMP,
    localCamera.z + normDir.z * BUMP
);
// i podobnie dla target - cofnąć o BUMP
```

### Fix 4: Zbadanie rzeczywistego pliku .vphys de_mirage
Sprawdzić liczbę i nazwy plików .vphys dla de_mirage na dysku CS2 i zregenerować .opt ze wszystkich.

---

## Podsumowanie

**Implementacja Java jest poprawna** — algorytm 1:1 z C++. 

**Główne przyczyny problemów na de_mirage:**
1. 🔴 **Plik de_mirage.opt jest niekompletny** — zaledwie 87k trójkątów vs 204k dla dust2; brakuje geometrii wielu ścian
2. 🟡 **Filtr MAX_EDGE_LENGTH=800f nie ma wpływu** na mirage (max edge=796, poniżej limitu), ale działa "przez przypadek" dla dust2 (usuwa wielkie trójkąty terenu)
3. 🟡 **Eye-height offset** +64f może powodować edge-case false positives przy kucaniu lub przy ścianie
