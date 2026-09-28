// Interface AIDL du « UserService » Shizuku.
//
// Le UserService s'exécute dans un processus séparé, avec l'identité shell
// (uid 2000) ou root (uid 0). C'est la méthode RECOMMANDÉE par Shizuku pour
// exécuter du code privilégié — elle remplace l'ancien Shizuku.newProcess,
// déprécié puis retiré de l'API publique (v13).
package com.ninja.splitlauncher;

interface IUserService {

    // Méthode « destroy » réservée par le serveur Shizuku (code de transaction
    // imposé). Appelée quand le service est arrêté.
    void destroy() = 16777114;

    // Exécute une commande shell avec l'identité privilégiée et renvoie le
    // résultat encodé : première ligne « exit=<code> », puis la sortie brute.
    String exec(String command) = 1;
}
