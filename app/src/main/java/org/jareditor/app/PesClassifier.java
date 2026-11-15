package org.jareditor.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * Descobre, so pelo conteudo, o que cada tabela de nomes do PES e:
 * jogadores, times, selecoes, estadios, campeonatos, idiomas, textos do jogo ou lixo binario.
 */
final class PesClassifier {
    static final String JOG = "Jogadores", TIM = "Times", SEL = "Seleções", EST = "Estádios",
        CAM = "Campeonatos", IDI = "Idiomas", TXT = "Textos do jogo", OUT = "Outros nomes",
        LIX = "Ocultos (sem sentido)";
    static final String[] ALL = { JOG, TIM, SEL, EST, CAM, IDI, TXT, OUT, LIX };

    private PesClassifier() {
    }

    static int order(String cat) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(cat)) return i;
        return ALL.length;
    }

    // ---------- listas de referencia (comparadas sem acento, em maiusculas) ----------
    private static HashSet<String> set(String... a) {
        HashSet<String> h = new HashSet<>();
        for (String s : a) h.add(norm(s));
        return h;
    }

    private static final HashSet<String> LANG = set("English", "Ingles", "Portugues", "Portuguese", "Espanol", "Spanish",
        "Espanhol", "Deutsch", "German", "Alemao", "Francais", "French", "Frances", "Italiano", "Italian", "Language",
        "Langue", "Sprache", "Idioma", "Lingua", "Lengua", "Japanese", "Japones", "Nederlands", "Svenska");

    private static final HashSet<String> COUNTRIES = set(
        "Brasil", "Brazil", "Argentina", "Uruguai", "Uruguay", "Paraguai", "Paraguay", "Chile", "Colombia", "Peru",
        "Equador", "Ecuador", "Venezuela", "Bolivia", "Mexico", "EUA", "Estados Unidos", "USA", "United States",
        "Canada", "Costa Rica", "Honduras", "Panama", "Jamaica", "Trinidad e Tobago", "El Salvador", "Cuba", "Haiti",
        "Alemanha", "Germany", "Deutschland", "Franca", "France", "Italia", "Italy", "Espanha", "Spain", "Espana",
        "Portugal", "Inglaterra", "England", "Escocia", "Scotland", "Pais de Gales", "Gales", "Wales", "Irlanda",
        "Ireland", "Irlanda do Norte", "Northern Ireland", "Holanda", "Netherlands", "Holland", "Paises Baixos",
        "Belgica", "Belgium", "Suica", "Switzerland", "Austria", "Suecia", "Sweden", "Noruega", "Norway", "Dinamarca",
        "Denmark", "Finlandia", "Finland", "Islandia", "Iceland", "Russia", "Ucrania", "Ukraine", "Polonia", "Poland",
        "Republica Tcheca", "Tchequia", "Czech Republic", "Eslovaquia", "Slovakia", "Hungria", "Hungary", "Romenia",
        "Romania", "Bulgaria", "Grecia", "Greece", "Turquia", "Turkey", "Croacia", "Croatia", "Servia", "Serbia",
        "Eslovenia", "Slovenia", "Bosnia", "Montenegro", "Albania", "Macedonia", "Israel", "Belarus", "Bielorrussia",
        "Georgia", "Armenia", "Azerbaijao", "Cazaquistao", "Letonia", "Lituania", "Estonia", "Luxemburgo",
        "Nigeria", "Camaroes", "Cameroon", "Gana", "Ghana", "Costa do Marfim", "Ivory Coast", "Senegal", "Egito",
        "Egypt", "Marrocos", "Morocco", "Argelia", "Algeria", "Tunisia", "Africa do Sul", "South Africa", "Mali",
        "Angola", "Togo", "Guine", "Congo", "Zambia", "Etiopia", "Quenia", "Kenya",
        "Japao", "Japan", "Coreia do Sul", "South Korea", "Korea", "Coreia do Norte", "China", "Arabia Saudita",
        "Saudi Arabia", "Ira", "Iran", "Iraque", "Iraq", "Australia", "Nova Zelandia", "New Zealand", "Catar", "Qatar",
        "Emirados Arabes", "Uzbequistao", "Tailandia", "Indonesia", "India", "Vietna");

    private static final HashSet<String> CLUBS = set(
        "Flamengo", "Corinthians", "Palmeiras", "Sao Paulo", "Santos", "Vasco", "Vasco da Gama", "Fluminense",
        "Botafogo", "Gremio", "Internacional", "Cruzeiro", "Atletico Mineiro", "Atletico Paranaense", "Sport", "Bahia",
        "Vitoria", "Fortaleza", "Ceara", "Goias", "Coritiba", "Parana", "America", "Ponte Preta", "Guarani",
        "Portuguesa", "Juventude", "Santa Cruz", "Nautico", "Avai", "Figueirense", "Chapecoense", "Athletico",
        "Arsenal", "Chelsea", "Liverpool", "Manchester United", "Manchester City", "Tottenham", "Everton", "Newcastle",
        "Aston Villa", "West Ham", "Leeds", "Fulham", "Wolves", "Leicester", "Southampton", "Sunderland",
        "Real Madrid", "Barcelona", "Atletico de Madrid", "Atletico Madrid", "Valencia", "Sevilla", "Villarreal",
        "Athletic Bilbao", "Real Sociedad", "Betis", "Deportivo", "Espanyol", "Malaga",
        "Juventus", "Milan", "AC Milan", "Inter", "Inter de Milao", "Internazionale", "Napoli", "Roma", "Lazio",
        "Fiorentina", "Parma", "Udinese", "Sampdoria", "Atalanta", "Genoa", "Torino", "Bologna",
        "Bayern", "Bayern de Munique", "Bayern Munchen", "Borussia Dortmund", "Schalke 04", "Werder Bremen",
        "Hamburgo", "Bayer Leverkusen", "Wolfsburg", "Stuttgart", "PSG", "Paris Saint Germain", "Marseille", "Marselha",
        "Lyon", "Monaco", "Lille", "Ajax", "PSV", "Feyenoord", "Porto", "Benfica", "Sporting", "Braga",
        "Celtic", "Rangers", "Galatasaray", "Fenerbahce", "Besiktas", "Olympiacos", "Boca Juniors", "River Plate",
        "Independiente", "Racing", "San Lorenzo", "Penarol", "Nacional", "Colo Colo", "Club America", "Chivas");

    private static final HashSet<String> CLUB_TOK = set("FC", "CF", "SC", "AC", "AS", "SV", "FK", "SK", "UNITED", "CITY",
        "REAL", "ATLETICO", "ATHLETIC", "SPORTING", "CLUB", "CLUBE", "DEPORTIVO", "BORUSSIA", "INTER", "TOWN",
        "ROVERS", "WANDERERS", "ALBION", "RANGERS", "JUNIORS", "ESPORTE", "ASSOCIACAO");
    private static final HashSet<String> EST_TOK = set("ESTADIO", "STADIUM", "STADE", "STADION", "STADIO", "ARENA",
        "PARK", "MARACANA", "MORUMBI", "ESTADIOS", "STADIUMS");
    private static final HashSet<String> CAM_TOK = set("COPA", "CUP", "LIGA", "LEAGUE", "CHAMPIONS", "SERIE", "TACA",
        "CAMPEONATO", "TROPHY", "TROFEU", "SUPERCOPA", "LIBERTADORES", "BRASILEIRAO", "PREMIER", "BUNDESLIGA",
        "CALCIO", "DIVISION", "DIVISAO", "SUPERLIGA", "EUROCOPA", "COMPETICAO", "TORNEIO", "TOURNAMENT");

    // ---------- utilitarios ----------
    /** Maiusculas, sem acento, so letras/numeros separados por um espaco. */
    static String norm(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        StringBuilder b = new StringBuilder();
        boolean sp = true;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(Character.toUpperCase(c));
                sp = false;
            } else if (!sp) {
                b.append(' ');
                sp = true;
            }
        }
        return b.toString().trim();
    }

    private static boolean hasTok(String u, HashSet<String> toks) {
        for (String w : u.split(" ")) if (toks.contains(w)) return true;
        return false;
    }

    /** Parece nome de pessoa/time/lugar: poucas letras acentuadas, sem simbolos estranhos, com vogais. */
    static boolean nameLike(String s) {
        s = s.trim();
        int n = s.length();
        if (n < 2 || n > 34) return false;
        int letters = 0, accent = 0, digits = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c > 127) accent++;
            } else if (c >= '0' && c <= '9') {
                digits++;
            } else if (c == ' ' || c == '.' || c == '\'' || c == '-' || c == '&' || c == '/' || c == '\u2019') {
                // pontuacao comum em nomes
            } else {
                return false;
            }
        }
        if (letters < 2 || digits > 2 || accent * 4 > letters) return false;
        String nm = norm(s).replace(" ", "");
        if (nm.length() >= 4) {
            int v = 0;
            for (int i = 0; i < nm.length(); i++) if ("AEIOUY".indexOf(nm.charAt(i)) >= 0) v++;
            double r = v / (double) nm.length();
            if (r < 0.15 || r > 0.8) return false;
        }
        return true;
    }

    /** Parece frase/texto de menu: varias palavras, quase so letras. */
    static boolean textLike(String s) {
        s = s.trim();
        int n = s.length();
        if (n < 8) return false;
        int letters = 0, spaces = 0, other = 0, accent = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c > 127) accent++;
            } else if (c == ' ') {
                spaces++;
            } else if (!(c >= '0' && c <= '9') && ".,!?:;'-()%/\"".indexOf(c) < 0) {
                other++;
            }
        }
        return spaces >= 2 && letters * 100 >= n * 60 && other * 10 <= n && accent * 4 <= letters;
    }

    /** Categoria de uma tabela, olhando todos os nomes dela. */
    static String classify(List<String> names) {
        int n = names.size();
        if (n == 0) return LIX;
        int nameOk = 0, textOk = 0;
        for (String s : names) {
            if (nameLike(s)) nameOk++;
            if (textLike(s)) textOk++;
        }
        if (textOk * 100 >= n * 40) return TXT;
        if (nameOk * 100 < n * 50) return LIX;
        if (nameOk * 100 < n * 80) return OUT;

        int lang = 0, sel = 0, club = 0, est = 0, cam = 0;
        for (String s : names) {
            if (!nameLike(s)) continue;
            String u = norm(s);
            if (LANG.contains(u)) lang++;
            if (COUNTRIES.contains(u)) sel++;
            if (CLUBS.contains(u) || hasTok(u, CLUB_TOK)) club++;
            if (hasTok(u, EST_TOK)) est++;
            if (hasTok(u, CAM_TOK)) cam++;
        }
        if (lang >= 3 && lang * 100 >= n * 25) return IDI;
        if (sel >= 5 && sel * 100 >= n * 30) return SEL;
        if (est >= 3 && est * 100 >= n * 30) return EST;
        if (cam >= 3 && cam * 100 >= n * 30) return CAM;
        if (club >= 4 && club * 100 >= n * 25) return TIM;
        if (n >= 20) return JOG;
        return OUT;
    }

    static List<String> allNames() {
        return new ArrayList<>(Arrays.asList(ALL));
    }
}
