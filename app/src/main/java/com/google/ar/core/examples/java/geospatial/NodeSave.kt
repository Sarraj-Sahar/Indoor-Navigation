package com.google.ar.core.examples.java.geospatial

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation.findNavController

/**
 * A simple [Fragment] subclass.
 * Use the [NodeSave.newInstance] factory method to
 * create an instance of this fragment.
 */
class NodeSave : Fragment() {
    private var SaveDestination: Button? = null
    private var SaveWalkable: Button? = null

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
        val view = inflater.inflate(R.layout.fragment_node_save, container, false)
        SaveDestination = view.findViewById(R.id.saveDestinationBtn)
        SaveDestination!!.setOnClickListener(View.OnClickListener { view ->
            findNavController(view).navigate(
                R.id.action_nodeSave_to_saveDestination
            )
        })
        SaveWalkable = view.findViewById(R.id.saveWalkableBtn)
        SaveWalkable!!.setOnClickListener(View.OnClickListener { view ->
            findNavController(view).navigate(
                R.id.action_nodeSave_to_saveWalkable
            )
        })
        return view
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
         * @return A new instance of fragment NodeSave.
         */
        // TODO: Rename and change types and number of parameters
        fun newInstance(param1: String?, param2: String?): NodeSave {
            val fragment = NodeSave()
            val args = Bundle()
            args.putString(ARG_PARAM1, param1)
            args.putString(ARG_PARAM2, param2)
            fragment.arguments = args
            return fragment
        }
    }
}