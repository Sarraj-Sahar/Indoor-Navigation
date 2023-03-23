package com.google.ar.core.examples.java.geospatial

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation.findNavController
import androidx.room.Room
import com.google.ar.core.examples.java.database.Node
import com.google.ar.core.examples.java.database.NodeDatabase

/**
 * A simple [Fragment] subclass.
 * Use the [SaveDestination.newInstance] factory method to
 * create an instance of this fragment.
 */
class SaveDestination : Fragment() {
    private var cancel: Button? = null
    private var save: Button? = null

    // TODO: Rename and change types of parameters
    private var mParam1: String? = null
    private var mParam2: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (arguments != null) {
            mParam1 = requireArguments().getString(ARG_PARAM1)
            mParam2 = requireArguments().getString(ARG_PARAM2)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_save_destination, container, false)
        cancel = view.findViewById(R.id.cancelBtn)
        cancel!!.setOnClickListener(View.OnClickListener { view -> findNavController(view).navigate(R.id.action_saveDestination_to_nodeSave) })
        save = view.findViewById(R.id.saveBtn)
        save!!.setOnClickListener(View.OnClickListener { view ->
            findNavController(view).navigate(R.id.action_saveDestination_to_nodeCreated)

            //saveNodeparams();
        })
        return view
    }

    private fun saveNodeparams(
        context: Context,
        id: Int,
        latitude: Float,
        longitude: Float,
        altitude: Float,
        name: String,
        adjs: MutableSet<Node>?
    ) {

        // create a new Node object with the anchor parameters
        val node = Node(id, latitude, longitude, altitude, name, adjs)

        // instantiate the database and insert the new Node object into the database
        val nodeDao =
            Room.databaseBuilder(context, NodeDatabase::class.java, "node-database").build()
                .nodeDao()
        nodeDao.insert(node)
    }

    companion object {
        // TODO: Rename parameter arguments, choose names that match
        // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
        private const val ARG_PARAM1 = "param1"
        private const val ARG_PARAM2 = "param2"

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment SaveDestination.
         */
        // TODO: Rename and change types and number of parameters
        fun newInstance(param1: String?, param2: String?): SaveDestination {
            val fragment = SaveDestination()
            val args = Bundle()
            args.putString(ARG_PARAM1, param1)
            args.putString(ARG_PARAM2, param2)
            fragment.arguments = args
            return fragment
        }
    }
}