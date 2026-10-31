package com.flipauto;
import android.os.Bundle;
import com.flipauto.IConnectivityListener;
interface IShellService {
    String execute(String operation, int displayId, int value, String component) = 1;
    Bundle taskSnapshot(int displayId, String task) = 2;
    void watchConnectivity(IConnectivityListener listener) = 3;
    void destroy() = 16777114;
}
